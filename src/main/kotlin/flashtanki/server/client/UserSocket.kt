package flashtanki.server.client

import com.squareup.moshi.Json
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import flashtanki.server.*
import flashtanki.server.battles.*
import flashtanki.server.battles.bonus.BattleBonus
import flashtanki.server.battles.map.IMapRegistry
import flashtanki.server.commands.*
import flashtanki.server.exceptions.UnknownCommandCategoryException
import flashtanki.server.exceptions.UnknownCommandException
import flashtanki.server.garage.*
import flashtanki.server.invite.IInviteService
import flashtanki.server.invite.Invite
import flashtanki.server.battles.killstreak.*
import flashtanki.server.lobby.chat.ILobbyChatManager
import flashtanki.server.news.NewsLoader
import flashtanki.server.news.ServerNewsData
import io.ktor.network.sockets.*
import io.ktor.utils.io.*
import kotlinx.coroutines.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import mu.KotlinLogging
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.koin.java.KoinJavaComponent
import java.io.IOException
import java.lang.reflect.InvocationTargetException
import java.time.format.DateTimeFormatter
import java.util.*
import kotlin.collections.component1
import kotlin.collections.component2
import kotlin.collections.set
import kotlin.coroutines.CoroutineContext
import kotlin.io.path.readText
import kotlin.random.Random
import kotlin.reflect.KParameter
import kotlin.reflect.full.callSuspendBy
import kotlin.reflect.full.primaryConstructor
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime

suspend fun Command.send(socket: UserSocket) = socket.send(this)
suspend fun Command.send(player: BattlePlayer) = player.socket.send(this)
suspend fun Command.send(tank: BattleTank) = tank.socket.send(this)

@JvmName("sendSockets") suspend fun Command.send(sockets: Iterable<UserSocket>) = sockets.forEach { socket -> socket.send(this) }
@JvmName("sendPlayers") suspend fun Command.send(players: Iterable<BattlePlayer>) = players.forEach { player -> player.socket.send(this) }
@JvmName("sendTanks") suspend fun Command.send(tanks: Iterable<BattleTank>) = tanks.forEach { tank -> tank.socket.send(this) }

suspend fun UserSocket.sendChat(message: String, warning: Boolean = false) = Command(
  CommandName.SendSystemChatMessageClient,
  message,
  warning.toString()
).send(this)

suspend fun UserSocket.sendBattleChat(message: String) {
  if(battle == null) throw IllegalStateException("Player is not in battle")

  Command(
    CommandName.SendBattleChatMessageClient,
    BattleChatMessage(
      nickname = "",
      rank = 0,
      chat_level = user?.chatModerator ?: ChatModeratorLevel.None,
      message = message,
      team = false,
      team_type = BattleTeam.None,
      system = true
    ).toJson()
  ).send(this)
}

class UserSocket(
  coroutineContext: CoroutineContext,
  private val socket: Socket
) : KoinComponent {
  private val logger = KotlinLogging.logger { }

  private val packetProcessor = PacketProcessor()
  private val encryption = EncryptionTransformer()
  private val server by inject<ISocketServer>()
  private val commandRegistry by inject<ICommandRegistry>()
  private val resourceManager by inject<IResourceManager>()
  private val marketRegistry by inject<IGarageMarketRegistry>()
  private val mapRegistry by inject<IMapRegistry>()
  private val garageItemConverter by inject<IGarageItemConverter>()
  private val battleProcessor by inject<IBattleProcessor>()
  private val lobbyChatManager by inject<ILobbyChatManager>()
  private val userRepository by inject<IUserRepository>()
  private val inviteService by inject<IInviteService>()
  private val resourceConverter by inject<IResourceConverter>()
  private val json by inject<Moshi>()

  private val input: ByteReadChannel = socket.openReadChannel()
  private val output: ByteWriteChannel = socket.openWriteChannel(autoFlush = true)
  public var acceptedFriends: MutableList<FriendEntry> = mutableListOf<FriendEntry>()
  public var incomingFriends: MutableList<FriendEntry> = mutableListOf<FriendEntry>()
  public var outcomingFriends: MutableList<FriendEntry> = mutableListOf<FriendEntry>()

  private val lock: Semaphore = Semaphore(1)
  val battleJoinLock: Semaphore = Semaphore(1)
  // private val sendQueue: Queue<Command> = LinkedList()

  val coroutineScope = CoroutineScope(coroutineContext + SupervisorJob())

  val remoteAddress: SocketAddress = socket.remoteAddress

  var active: Boolean = false

  var locale: SocketLocale? = null

  var user: User? = null
  var selectedBattle: Battle? = null
  var screen: Screen? = null

  var invite: Invite? = null

  var captcha: MutableMap<CaptchaLocation, String> = mutableMapOf()
  var captchaUpdateTimestamps: MutableMap<String, MutableList<Long>> = mutableMapOf()

  var userBattleCreationCount = mutableMapOf<String, Int>() // Хранение количества созданных битв для каждого пользователя
  var userLastBattleCreationTime = mutableMapOf<String, Long>() // Хранение времени последнего создания битвы для каждого пользователя
  val userLastBattleResetTime = mutableMapOf<String, Long>()
  var weaponDelayMount = 0
  var hullDelayMount = 0
  var colormapDelayMount = 0
  var snId: String = "none"
  var showGoldAuthor: Boolean = false

  var sentAuthResources: Boolean = false

  val battle: Battle?
    get() = battlePlayer?.battle

  val battlePlayer: BattlePlayer?
    get() = battleProcessor.battles
      .flatMap { battle -> battle.players }
      .singleOrNull { player -> player.socket == this }

  private var clientRank: UserRank? = null

  suspend fun deactivate() {
    active = false

    val player = battlePlayer
    if(player != null) { // Remove player from battle
      player.deactivate(terminate = true)
      player.battle.players.remove(player)
    }

    server.players.remove(this)

    withContext(Dispatchers.IO) {
      if(!socket.isClosed) {
        try {
          socket.close()
        } catch(exception: IOException) {
          logger.error(exception) { "Failed to close socket" }
        }
      }
    }

    coroutineScope.cancel()
  }

  suspend fun send(command: Command) {
    lock.withPermit {
      try {
        output.writeFully(command.serialize().toByteArray())
      } catch(exception: IOException) {
        logger.warn(exception) { "$this thrown an exception" }
        deactivate()
        return
      }

      if(
        command.name != CommandName.Pong &&
        command.name != CommandName.ClientMove &&
        command.name != CommandName.ClientFullMove &&
        command.name != CommandName.ClientRotateTurret &&
        command.name != CommandName.ClientMovementControl        
      ) { // Too verbose
        if(
          command.name == CommandName.LoadResources ||
          command.name == CommandName.InitLocale ||
          command.name == CommandName.UpdateCaptcha ||
          command.name == CommandName.InitShotsData ||
          command.name == CommandName.InitGarageItems ||
          command.name == CommandName.InitGarageMarket
        ) { // Too long
          logger.trace { "Sent command ${command.name} ${command.args.drop(2)} to $this" }
        } else {
          logger.trace { "Sent command ${command.name} ${command.args} to $this" }
        }
      }
    }
  }

  val dependencies: MutableMap<Int, ClientDependency> = mutableMapOf()
  private var lastDependencyId = 1

  // TODO(Assasans): Rename
  suspend fun loadDependency(resources: String): ClientDependency {
    val dependency = ClientDependency(
      id = lastDependencyId++,
      deferred = CompletableDeferred()
    )
    dependencies[dependency.id] = dependency

    Command(
      CommandName.LoadResources,
      resources,
      dependency.id.toString()
    ).send(this)

    return dependency
  }

  suspend fun addPremiumAccount(premium: Int) {
    val user = user ?: throw Exception("No User")
    val entityManager = HibernateUtils.createEntityManager()
    val transaction = entityManager.transaction

    var showWelcomeAlert: Boolean = false
    var showAlertForFirstPurchasePremium: Boolean = false

    try {
      transaction.begin()

      val currentInstant = Clock.System.now()
      if (user.premium <= 0) {
        // Create the identifier for ServerGarageUserItemPaint
        val paintItemId = ServerGarageItemId(user, "premium")
        val existingPaintItem = withContext(Dispatchers.IO) {
          entityManager.find(ServerGarageUserItemPaint::class.java, paintItemId)
        }

        if (existingPaintItem == null) {
          //val paintItem = ServerGarageUserItemPaint(user, "premium")
          //user.items += paintItem
          //entityManager.persist(paintItem)
          showWelcomeAlert = true
          showAlertForFirstPurchasePremium = true
        }
      }

      val nextDayInstant = currentInstant.plus((user.premium / 86400).days)

      user.premium += premium * 86400

      userRepository.updateUser(user)

      transaction.commit()

      Command(
        CommandName.InitPremium,
        InitPremiumData(
          left_time = user.premium,
          needShowNotificationCompletionPremium = false,
          needShowWelcomeAlert = showWelcomeAlert,
          wasShowAlertForFirstPurchasePremium = showAlertForFirstPurchasePremium,
          wasShowReminderCompletionPremium = false
        ).toJson()
      ).send(this)

    } catch (e: Exception) {
      transaction.rollback()
      throw e
    } finally {
      entityManager.close()
    }
  }

  private suspend fun processPacket(packet: String) {
    var decrypted: String? = null
    try {
      // val end = packet.takeLast(Command.Delimiter.length)
      // if(end != Command.Delimiter) throw Exception("Invalid packet end: $end")

      // val decrypted = encryption.decrypt(packet.dropLast(Command.Delimiter.length))
      if(packet.isEmpty()) return

      // logger.debug { "PKT: $packet" }
      try {
        decrypted = encryption.decrypt(packet)
      } catch(exception: Exception) {
        logger.warn { "Failed to decrypt packet: $packet" }
        return
      }

      // logger.debug { "Decrypt: $packet -> $decrypted" }

      val command = Command()
      try {
        command.readFrom(decrypted.toByteArray())
      } catch(exception: Exception) {
        logger.warn { "Failed to decode command" }
        logger.warn { "- Raw packet: $packet" }
        logger.warn { "- Decrypted packet: $decrypted" }
        return
      }

      if(
        command.name != CommandName.Ping &&
        command.name != CommandName.Move &&
        command.name != CommandName.FullMove &&
        command.name != CommandName.RotateTurret &&
        command.name != CommandName.MovementControl
      ) { // Too verbose
        logger.trace { "Received command ${command.name} ${command.args}" }
      }

      if(command.side != CommandSide.Server) throw Exception("Unsupported command: ${command.category}::${command.name}")

      val handler = commandRegistry.getHandler(command.name)
      if(handler == null) return

      val args = mutableMapOf<KParameter, Any?>()
      try {
        val instance = handler.type.primaryConstructor!!.call()
        args += mapOf(
          Pair(handler.function.parameters.single { parameter -> parameter.kind == KParameter.Kind.INSTANCE }, instance),
          Pair(handler.function.parameters.filter { parameter -> parameter.kind == KParameter.Kind.VALUE }[0], this)
        )

        when(handler.argsBehaviour) {
          ArgsBehaviourType.Arguments -> {
            if(command.args.size < handler.args.size) throw IllegalArgumentException("Command has too few arguments. Packet: ${command.args.size}, handler: ${handler.args.size}")
            args.putAll(handler.args.mapIndexed { index, parameter ->
              val value = command.args[index]

              Pair(parameter, CommandArgs.convert(parameter.type, value))
            })
          }

          ArgsBehaviourType.Raw       -> {
            val argsParameter = handler.function.parameters.filter { parameter -> parameter.kind == KParameter.Kind.VALUE }[1]
            args[argsParameter] = CommandArgs(command.args)
          }
        }

        // logger.debug { "Handler ${handler.name} call arguments: ${args.map { argument -> "${argument.key.type}" }}" }
      } catch(exception: Throwable) {
        logger.error(exception) { "Failed to process ${command.name} arguments" }
        return
      }

      try {
        handler.function.callSuspendBy(args)
      } catch(exception: Throwable) {
        val targetException = if(exception is InvocationTargetException) exception.cause else exception
        logger.error(targetException) { "Failed to call ${command.name} handler" }
      }
    } catch(exception: UnknownCommandCategoryException) {
      logger.warn { "Unknown command category: ${exception.category}" }

      if(!Command.CategoryRegex.matches(exception.category)) {
        logger.warn { "The command category does not seem to be valid, most likely a decryption error." }
        logger.warn { "Please report this issue to the GitHub repository along with the following information:" }
        logger.warn { "- Raw packet: $packet" }
        logger.warn { "- Decrypted packet: $decrypted" }
      }
    } catch(exception: UnknownCommandException) {
      logger.warn { "Unknown command: ${exception.category}::${exception.command}" }
    } catch(exception: Exception) {
      logger.error(exception) { "An exception occurred" }
    }
  }

  suspend fun initBattleLoad() {
    Command(CommandName.StartLayoutSwitch, "BATTLE").send(this)
    Command(CommandName.UnloadBattleSelect).send(this)
    Command(CommandName.StartBattle).send(this)
    Command(CommandName.UnloadChat).send(this)
  }

  suspend fun handle() {
    active = true

    try {
      while(!(input.isClosedForRead || input.isClosedForWrite)) {
        val buffer: ByteArray
        try {
          buffer = input.readAvailable()
          packetProcessor.write(buffer)
        } catch(exception: IOException) {
          logger.warn(exception) { "$this thrown an exception" }
          deactivate()

          break
        }

        // val packets = String(buffer).split(Command.Delimiter)

        // for(packet in packets) {
        // ClientDependency.await() can deadlock execution if suspended
        //   coroutineScope.launch { processPacket(packet) }
        // }

        while(true) {
          val packet = packetProcessor.tryGetPacket() ?: break

          // ClientDependency.await() can deadlock execution if suspended
          coroutineScope.launch { processPacket(packet) }
        }
      }

      logger.debug { "$this end of data" }

      deactivate()
    } catch(exception: CancellationException) {
      logger.debug(exception) { "$this coroutine cancelled" }
    } catch(exception: Exception) {
      logger.error(exception) { "An exception occurred" }

      // withContext(Dispatchers.IO) {
      //   socket.close()
      // }
    }
  }

  suspend fun loadGarageResources() {
    loadDependency(resourceManager.get("resources/garage.json").readText()).await()
  }

  suspend fun loadLobbyResources() {
    loadDependency(resourceManager.get("resources/lobby.json").readText()).await()
  }

  suspend fun updateRating() {
    Command(CommandName.UpdateRating, Random.nextInt(1000, 9999).toString(), Random.nextInt(1000, 9999).toString(), Random.nextInt(1000, 9999).toString()).send(this)
  }

  @OptIn(ExperimentalTime::class)
  suspend fun loadLobby() {
    Command(CommandName.StartLayoutSwitch, "BATTLE_SELECT").send(this)

    screen = Screen.BattleSelect

    if(inviteService.enabled && !sentAuthResources) {
      sentAuthResources = true
      loadDependency(resourceManager.get("resources/auth.json").readText()).await()
    }

    val user = user ?: throw Exception("No User")

    clientRank = user.rank
    Command(
      CommandName.InitPanel,
      InitPanelData(
        name = user.username,
        crystall = user.crystals,
        rang = user.rank.value,
        score = user.score,
        currentRankScore = user.rank.scoreOrZero,
        next_score = user.rank.nextRank.scoreOrZero
      ).toJson()
    ).send(this)
	Command(CommandName.InitRefferalModel).send(this)
    updateRating()
    Command(
       CommandName.InitPremium,
       InitPremiumData(
            left_time = user.premium,
            needShowNotificationCompletionPremium = false,
            needShowWelcomeAlert = false,
            wasShowAlertForFirstPurchasePremium = false,
            wasShowReminderCompletionPremium = false
        ).toJson()
    ).send(this)
    Command(CommandName.InitClan, "{\"loadingInServiceSpace\":true,\"restrictionTimeJoinClanInSec\":60,\"giveBonusesClan\":false,\"clan\":false,\"showBuyLicenseButton\":true,\"showOtherClan\":true,\"clanMember\":false, \"flags\":[{\"image\":952497,\"id\":1,\"lang\":\"ru\"}]}").send(this)
    Command(CommandName.ShowNews, NewsLoader().loadNews(locale).toJson()).send(this)
    Command(
      CommandName.InitFriendsList,
      InitFriendsListData(
		friends = acceptedFriends,
		incoming = incomingFriends,
		outcoming = outcomingFriends
      ).toJson()
    ).send(this)

    loadLobbyResources()
    updateQuests()

    Command(CommandName.EndLayoutSwitch, "BATTLE_SELECT", "BATTLE_SELECT").send(this)

    Command(
      CommandName.ShowAchievements,
      ShowAchievementsData(ids = listOf(1, 3)).toJson()
    ).send(this)

    initChatMessages()
    initBattleList()

    if(user.premium > 1){
      val user = user ?: throw Exception("No User")
      println("User Premium: ${user.premium}")
      while(user.premium > 1 && user.premium != 1) {
        delay(60.seconds)
        user.premium -= 60
        userRepository.updateUser(user)
        println("Withdrawn 60 seconds User Premium: ${user.premium}")
      }
    }

  }

  suspend fun initClient() {
    val locale = locale ?: throw IllegalStateException("Socket locale is null")

    Command(CommandName.InitExternalModel, "http://localhost/").send(this)
    Command(
      CommandName.InitRegistrationModel,
      InitRegistrationModelData(
        enableRequiredEmail = false
      ).toJson()
    ).send(this)

    Command(CommandName.InitLocale, resourceManager.get("lang/${locale.key}.json").readText()).send(this)

    loadDependency(resourceManager.get("resources/auth-untrusted.json").readText()).await()
    if(!inviteService.enabled && !sentAuthResources) {
      sentAuthResources = true
      loadDependency(resourceManager.get("resources/auth.json").readText()).await()
    }

    Command(CommandName.InitInviteModel, inviteService.enabled.toString()).send(this)
    Command(CommandName.MainResourcesLoaded).send(this)
  }

suspend fun initBattleList() {
    val mapsFileName = when (locale) {
      SocketLocale.Russian -> "maps_ru.json"
      SocketLocale.English -> "maps_en.json"
      else -> "maps_en.json"
    }

    val mapsData = resourceManager.get(mapsFileName).readText()

    val mapsParsed = json
      .adapter<List<Map>>(Types.newParameterizedType(List::class.java, Map::class.java))
      .fromJson(mapsData)!!

    mapsParsed.forEach { userMap ->
      if (!mapRegistry.maps.any { map -> map.name == userMap.mapId && map.theme.clientKey == userMap.theme }) {
        userMap.enabled = false
        logger.warn { "Map ${userMap.mapId}@${userMap.theme} is missing" }
      }
    }

    Command(
      CommandName.InitBattleCreate,
      InitBattleCreateData(
        battleLimits = listOf(
          BattleLimit(battleMode = BattleMode.Deathmatch, scoreLimit = 999, timeLimitInSec = 59940),
          BattleLimit(battleMode = BattleMode.TeamDeathmatch, scoreLimit = 999, timeLimitInSec = 59940),
          BattleLimit(battleMode = BattleMode.CaptureTheFlag, scoreLimit = 999, timeLimitInSec = 59940),
          BattleLimit(battleMode = BattleMode.ControlPoints, scoreLimit = 999, timeLimitInSec = 59940),
		  BattleLimit(battleMode = BattleMode.Juggernaut, scoreLimit = 999, timeLimitInSec = 59940)
        ),
        maps = mapsParsed,
        battleCreationDisabled = (user?.rank?.value ?: 1) < 3,
      ).toJson()
    ).send(this)

  val visibleBattles = battleProcessor.battles.filter { !it.properties[BattleProperty.privateBattle] }

  Command(
    CommandName.InitBattleSelect,
    InitBattleSelectData(
      battles = visibleBattles.map { battle -> battle.toBattleData() }
    ).toJson()
  ).send(this)
}

  public suspend fun createFriend(username: String, rank: Int, online: Boolean): FriendEntry {
    val friend = FriendEntry(username, rank, online)
    return friend
  }

  data class Item(
    @Json(name = "itemViewCategory") val itemViewCategory: String,
    @Json(name = "preview") val preview: Int,
    @Json(name = "item") val item: String,
    @Json(name = "category") val category: String,
    @Json(name = "modification") val modification: Int,
    @Json(name = "name") val name: String,
    @Json(name = "position") val position: Int
  )

  data class OpenedItemsData(
    @Json(name = "items") val items: List<Item>
  )

  suspend fun OpenedItems() {
    val itemsList = listOf(
      Item(
        itemViewCategory = "weapon",
        preview = 770995,
        item = "thunder",
        category = "weapon",
        modification = 2,
        name = "Гром",
        position = 1
      )
    )
    val openedItemsData = OpenedItemsData(itemsList)

    Command(CommandName.OpenedItems, openedItemsData.toJson()).send(this)
  }

  suspend fun initGarage() {

    val entityManager = HibernateUtils.createEntityManager()

    val user = user ?: throw Exception("No User")
    val locale = locale ?: throw IllegalStateException("Socket locale is null")

    val itemsParsed = mutableListOf<GarageItem>()
    val marketParsed = mutableListOf<GarageItem>()

    val marketItems = marketRegistry.items

    /*if (user.premium == 0) {
      user.equipment.paintId = "green"
      entityManager.merge(user.equipment)
    }*/

    marketItems.forEach { (_, marketItem) ->
      val userItem = user.items.singleOrNull { it.marketItem == marketItem }
      val clientMarketItems = when(marketItem) {
        is ServerGarageItemWeapon -> garageItemConverter.toClientWeapon(marketItem, locale)
        is ServerGarageItemHull -> garageItemConverter.toClientHull(marketItem, locale)
        is ServerGarageItemResistance -> listOf(garageItemConverter.toClientResistance(marketItem, locale))
        is ServerGarageItemPaint -> listOf(garageItemConverter.toClientPaint(marketItem, locale))
        is ServerGarageItemSupply -> listOf(garageItemConverter.toClientSupply(marketItem, userItem as ServerGarageUserItemSupply?, locale))
        is ServerGarageItemSubscription -> listOf(garageItemConverter.toClientSubscription(marketItem, userItem as ServerGarageUserItemSubscription?, locale))
        is ServerGarageItemKit -> listOf(garageItemConverter.toClientKit(marketItem, locale))
        is ServerGarageItemPresent -> listOf(garageItemConverter.toClientPresent(marketItem, locale))
        is ServerGarageItemLootbox -> listOf(garageItemConverter.toClientLootbox(marketItem, userItem as ServerGarageUserItemLootbox?, locale))

        else -> throw NotImplementedError("Not implemented: ${marketItem::class.simpleName}")
      }

      // if(marketItem is ServerGarageItemSupply) return@forEach
      // if(marketItem is ServerGarageItemSubscription) return@forEach
      // if(marketItem is ServerGarageItemKit) return@forEach

      if(userItem != null) {
        if(userItem is ServerGarageUserItemWithModification) {
          clientMarketItems.forEach clientMarketItems@{ clientItem ->
            // Add current and previous modifications as user items
            // if(clientItem.modificationID!! <= userItem.modification) itemsParsed.add(clientItem)

            // if(clientItem.modificationID!! < userItem.modification) return@clientMarketItems
            if(clientItem.modificationID == userItem.modificationIndex) itemsParsed.add(clientItem)
            else marketParsed.add(clientItem)
          }
        } else {
          itemsParsed.addAll(clientMarketItems)
        }
      } else {
        // Add market item
        marketParsed.addAll(clientMarketItems)
      }
    }

    marketParsed
      .filter { item -> item.type == GarageItemType.Kit }
      .forEach { item ->
        if(item.kit == null) throw Exception("Kit is null")

        val ownsAll = item.kit.kitItems.all { kitItem ->
          val id = kitItem.id.substringBeforeLast("_")
          val modification = kitItem.id
            .substringAfterLast("_")
            .drop(1) // Drop 'm' letter
            .toInt()

          marketParsed.none { marketItem -> marketItem.id == id && marketItem.modificationID == modification }
        }

        val suppliesOnly = item.kit.kitItems.all { kitItem ->
          val id = kitItem.id.substringBeforeLast("_")
          val marketItem = marketRegistry.get(id)
          marketItem is ServerGarageItemSupply
        }

        if(ownsAll && !suppliesOnly) {
          marketParsed.remove(item)

          logger.debug { "Removed kit ${item.name} from market: user owns all items" }
        }
      }

    marketParsed
      .filter { item -> item.index < 0 }
      .forEach { item -> marketParsed.remove(item) }

    Command(CommandName.InitGarageItems, InitGarageItemsData(items = itemsParsed).toJson()).send(this)
    Command(
      CommandName.InitMountedItem,
      user.equipment.hull.mountName, user.equipment.hull.modification.object3ds.toString()
    ).send(this)
    Command(
      CommandName.InitMountedItem,
      user.equipment.weapon.mountName, user.equipment.weapon.modification.object3ds.toString()
    ).send(this)
    val coloring = user.equipment.paint.marketItem.animatedColoring ?: user.equipment.paint.marketItem.coloring
    Command(CommandName.InitMountedItem, user.equipment.paint.mountName, coloring.toString()).send(this)
	Command(CommandName.InitMountedItem, user.equipment.resistance.mountName, "").send(this)
    Command(CommandName.InitGarageMarket, InitGarageMarketData(items = marketParsed, delayMountArmorInSec = hullDelayMount, delayMountWeaponInSec = weaponDelayMount, delayMountColorInSec = colormapDelayMount).toJson()).send(this)

    // logger.debug { "User items:" }
    // itemsParsed
    //   .filter { item -> item.type != GarageItemType.Paint }
    //   .forEach { item -> logger.debug { "  > ${item.name} (m${item.modificationID})" } }
    //
    // logger.debug { "Market items:" }
    // marketParsed
    //   .filter { item -> item.type != GarageItemType.Paint }
    //   .forEach { item -> logger.debug { "  > ${item.name} (m${item.modificationID})" } }
  }

  suspend fun updateCrystals() {
    val user = user ?: throw Exception("User data is not loaded")
    if(screen == null) return // Protect manual-packets

    Command(CommandName.SetCrystals, user.crystals.toString()).send(this)
    updateRating()
  }

  suspend fun updateScore() {
    val user = user ?: throw Exception("User data is not loaded")
    if(screen == null) return // Protect manual-packets

    Command(CommandName.SetScore, user.score.toString()).send(this)

    if(user.rank == clientRank) return // No need to update rank
    clientRank = user.rank

    Command(
      CommandName.SetRank,
      user.rank.value.toString(),
      user.score.toString(),
      user.rank.score.toString(),
      user.rank.nextRank.scoreOrZero.toString(),
      user.rank.bonusCrystals.toString()
    ).send(this)
    battle?.let { battle ->
      Command(CommandName.SetBattleRank, user.username, user.rank.value.toString()).sendTo(battle)
    }

    user.crystals += user.rank.bonusCrystals

    updateCrystals()
    updateRating()
    OpenedItems()

    if(screen == Screen.Garage) {
      // Refresh garage to prevent items from being duplicated (client-side bug)
      // and update available items
      Command(CommandName.UnloadGarage).send(this)

      loadGarageResources()
      initGarage()
    }
  }

  suspend fun updateQuests() {
    val user = user ?: throw Exception("No User")

    var notifyNew = false
    user.dailyQuests
      .filter { quest -> quest.new }
      .forEach { quest ->
        quest.new = false
        quest.updateProgress()
        notifyNew = true
      }

    if(notifyNew) {
      Command(CommandName.NotifyQuestsNew).send(this)
    }

    var notifyCompleted = false
    user.dailyQuests
      .filter { quest -> quest.current >= quest.required && !quest.completed }
      .forEach { quest ->
        quest.completed = true
        notifyCompleted = true
      }

    if(notifyCompleted) {
      Command(CommandName.NotifyQuestCompleted).send(this)
    }
  }

  suspend fun initChatMessages() {
    val user = user ?: throw Exception("User data is not loaded")

    val time = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
    val formatter = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss.SSS", Locale.ROOT)

    val registeredPlayers = userRepository.getUserCount()

    Command(
      CommandName.InitMessages,
      InitChatMessagesData(
        messages = lobbyChatManager.messages,
		news = NewsLoader().loadNews(locale)
      ).toJson(),
      InitChatSettings(
        selfName = user.username,
        chatModeratorLevel = user.chatModeratorLevel,
      ).toJson()
    ).send(this)
  }

  suspend fun ban(until: Instant, reason: String? = null) {
    user?.setBanInfo(until, reason)

    deactivate()
  }

  override fun toString(): String = buildString {
    when(remoteAddress) {
      is InetSocketAddress -> append("${remoteAddress.hostname}:${remoteAddress.port}")
      else                 -> append(remoteAddress)
    }

    user?.let { user -> append(" (${user.username})") }
  }
}

data class InitBonus(
  @Json val id: String,
  @Json val position: Vector3Data,
  @Json val timeFromAppearing: Int, // In milliseconds
  @Json val timeLife: Int, // In seconds
  @Json val bonusFallSpeed: Int = 500 // Unused
)

fun BattleBonus.toInitBonus() = InitBonus(
  id = key,
  position = position.toVectorData(),
  timeFromAppearing = aliveFor.inWholeMilliseconds.toInt(),
  timeLife = -1
)

inline fun <reified T : Any> T.toJson(json: Moshi): String {
  return json.adapter(T::class.java).toJson(this)
}

inline fun <reified T : Any> T.toJson(): String {
  val json = KoinJavaComponent.inject<Moshi>(Moshi::class.java).value
  return json.adapter(T::class.java).toJson(this)
}

fun <T : Any> Moshi.toJson(value: T): String {
  return adapter<T>(value::class.java).toJson(value)
}

data class InitBattleModelData(
  @Json val battleId: String,
  @Json val map_id: String,
  @Json val mapId: Int,
  @Json val kick_period_ms: Int = 125000,
  @Json val invisible_time: Int = 5000,
  @Json val spectator: Boolean = true,
  @Json val reArmorEnabled: Boolean,
  @Json val active: Boolean = true,
  @Json val dustParticle: Int = 110001,
  @Json val minRank: Int,
  @Json val maxRank: Int,
  @Json val skybox: String,
  @Json val sound_id: Int = 584396,
  @Json val map_graphic_data: String
)

data class BonusLightingData(
  @Json val attenuationBegin: Int = 100,
  @Json val attenuationEnd: Int = 500,
  @Json val color: Int,
  @Json val intensity: Int = 1,
  @Json val time: Int = 0
)

data class BonusData(
  @Json val lighting: BonusLightingData,
  @Json val id: String,
  @Json val resourceId: Int,
  @Json val lifeTime: Int = 30
)

data class InitBonusesDataData(
  @Json val bonuses: List<BonusData>,
  @Json val cordResource: Int = 1000065,
  @Json val parachuteInnerResource: Int = 170005,
  @Json val parachuteResource: Int = 170004,
  @Json val pickupSoundResource: Int = 269321
)

data class ShowFriendsModalData(
  @Json val new_incoming_friends: List<FriendEntry> = listOf(),
  @Json val new_accepted_friends: List<FriendEntry> = listOf()
)

data class BattleUser(
  @Json val user: String,
  @Json val kills: Int = 0,
  @Json val score: Int = 0,
  @Json val suspicious: Boolean = false
)

abstract class ShowBattleInfoData(
  @Json val itemId: String,
  @Json val battleMode: BattleMode,
  @Json val scoreLimit: Int,
  @Json val timeLimitInSec: Int,
  @Json val preview: Int,
  @Json val maxPeopleCount: Int,
  @Json val name: String,
  @Json val proBattle: Boolean,
  @Json val minRank: Int,
  @Json val maxRank: Int,
  @Json val roundStarted: Boolean = true,
  @Json val spectator: Boolean,
  @Json val withoutBonuses: Boolean,
  @Json val withoutCrystals: Boolean,
  @Json val withoutSupplies: Boolean,
  @Json val reArmorEnabled: Boolean,
  @Json val proBattleEnterPrice: Int = 0,
  @Json val timeLeftInSec: Int,
  @Json val userPaidNoSuppliesBattle: Boolean = false,
  @Json val proBattleTimeLeftInSec: Int = -1,
  @Json val equipmentConstraintsMode: EquipmentConstraintsMode = EquipmentConstraintsMode.None,
  @Json val parkourMode: Boolean
)

class ShowTeamBattleInfoData(
  itemId: String,
  battleMode: BattleMode,
  scoreLimit: Int,
  timeLimitInSec: Int,
  preview: Int,
  maxPeopleCount: Int,
  name: String,
  proBattle: Boolean,
  minRank: Int,
  maxRank: Int,
  roundStarted: Boolean = true,
  spectator: Boolean,
  withoutBonuses: Boolean,
  withoutCrystals: Boolean,
  withoutSupplies: Boolean,
  reArmorEnabled: Boolean,
  proBattleEnterPrice: Int = 0,
  timeLeftInSec: Int,
  userPaidNoSuppliesBattle: Boolean = false,
  proBattleTimeLeftInSec: Int = -1,
  equipmentConstraintsMode: EquipmentConstraintsMode = EquipmentConstraintsMode.None,
  parkourMode: Boolean,

  @Json val usersRed: List<BattleUser>,
  @Json val usersBlue: List<BattleUser>,

  @Json val scoreRed: Int = 0,
  @Json val scoreBlue: Int = 0,

  @Json val autoBalance: Boolean,
  @Json val friendlyFire: Boolean,
) : ShowBattleInfoData(
  itemId,
  battleMode,
  scoreLimit,
  timeLimitInSec,
  preview,
  maxPeopleCount,
  name,
  proBattle,
  minRank,
  maxRank,
  roundStarted,
  spectator,
  withoutBonuses,
  withoutCrystals,
  withoutSupplies,
  reArmorEnabled,
  proBattleEnterPrice,
  timeLeftInSec,
  userPaidNoSuppliesBattle,
  proBattleTimeLeftInSec,
  equipmentConstraintsMode,
  parkourMode
)

class ShowDmBattleInfoData(
  itemId: String,
  battleMode: BattleMode,
  scoreLimit: Int,
  timeLimitInSec: Int,
  preview: Int,
  maxPeopleCount: Int,
  name: String,
  proBattle: Boolean,
  minRank: Int,
  maxRank: Int,
  roundStarted: Boolean = true,
  spectator: Boolean,
  withoutBonuses: Boolean,
  withoutCrystals: Boolean,
  withoutSupplies: Boolean,
  reArmorEnabled: Boolean,
  proBattleEnterPrice: Int = 0,
  timeLeftInSec: Int,
  userPaidNoSuppliesBattle: Boolean = false,
  proBattleTimeLeftInSec: Int = -1,
  equipmentConstraintsMode: EquipmentConstraintsMode = EquipmentConstraintsMode.None,
  parkourMode: Boolean,

  @Json val users: List<BattleUser>,
) : ShowBattleInfoData(
  itemId,
  battleMode,
  scoreLimit,
  timeLimitInSec,
  preview,
  maxPeopleCount,
  name,
  proBattle,
  minRank,
  maxRank,
  roundStarted,
  spectator,
  withoutBonuses,
  withoutCrystals,
  withoutSupplies,
  reArmorEnabled,
  proBattleEnterPrice,
  timeLeftInSec,
  userPaidNoSuppliesBattle,
  proBattleTimeLeftInSec,
  equipmentConstraintsMode,
  parkourMode
)

abstract class BattleData(
  @Json val battleId: String,
  @Json val battleMode: BattleMode,
  @Json val map: String,
  @Json val maxPeople: Int,
  @Json val name: String,
  @Json val privateBattle: Boolean = false,
  @Json val proBattle: Boolean,
  @Json val minRank: Int,
  @Json val maxRank: Int,
  @Json val preview: Int,
  @Json val equipmentConstraintsMode: EquipmentConstraintsMode = EquipmentConstraintsMode.None,
  @Json val parkourMode: Boolean,
  @Json val timeLeft: Int,
  @Json val suspicious: Boolean = false
)

class DmBattleData(
  battleId: String,
  battleMode: BattleMode,
  map: String,
  maxPeople: Int,
  name: String,
  privateBattle: Boolean = false,
  proBattle: Boolean,
  minRank: Int,
  maxRank: Int,
  preview: Int,
  equipmentConstraintsMode: EquipmentConstraintsMode = EquipmentConstraintsMode.None,
  parkourMode: Boolean,
  timeLeft: Int,
  suspicious: Boolean = false,

  @Json val users: List<String>
) : BattleData(
  battleId,
  battleMode,
  map,
  maxPeople,
  name,
  privateBattle,
  proBattle,
  minRank,
  maxRank,
  preview,
  equipmentConstraintsMode,
  parkourMode,
  timeLeft,
  suspicious
)

class TeamBattleData(
  battleId: String,
  battleMode: BattleMode,
  map: String,
  maxPeople: Int,
  name: String,
  privateBattle: Boolean = false,
  proBattle: Boolean,
  minRank: Int,
  maxRank: Int,
  preview: Int,
  equipmentConstraintsMode: EquipmentConstraintsMode = EquipmentConstraintsMode.None,
  parkourMode: Boolean,
  timeLeft: Int,
  suspicious: Boolean = false,

  @Json val usersRed: List<String>,
  @Json val usersBlue: List<String>
) : BattleData(
  battleId,
  battleMode,
  map,
  maxPeople,
  name,
  privateBattle,
  proBattle,
  minRank,
  maxRank,
  preview,
  equipmentConstraintsMode,
  parkourMode,
  timeLeft,
  suspicious
)

data class InitBattleSelectData(
  @Json val battles: List<BattleData>
)

data class KillStreaksData(
  @Json val killStreaks: List<KillStreak>
)

data class ShowBonusesPresentData(
  @Json val image: Int,
  @Json val bottomText: String = "",
  @Json val topText: String = ""
)

data class BattleLimit(
  @Json val battleMode: BattleMode,
  @Json val scoreLimit: Int,
  @Json val timeLimitInSec: Int,
)

data class Map(
  @Json var enabled: Boolean = true,
  @Json val mapId: String,
  @Json val mapName: String,
  @Json var maxPeople: Int,
  @Json val preview: Int,
  @Json val maxRank: Int,
  @Json val minRank: Int,
  @Json val supportedModes: List<String>,
  @Json val theme: String
)

data class MapInfo(
  val preview: Int,
  val maxPeople: Int
)

data class InitBattleCreateData(
  @Json val maxRangeLength: Int = 7,
  @Json val battleCreationDisabled: Boolean = false,
  @Json val battleLimits: List<BattleLimit>,
  @Json val maps: List<Map>
)

data class ShowAchievementsData(
  @Json val ids: List<Int>
)

data class ChatMessage(
  @Json val name: String,
  @Json val rang: Int,
  @Json val chatPermissions: ChatModeratorLevel = ChatModeratorLevel.None,
  @Json val message: String,
  @Json val addressed: Boolean = false,
  @Json val chatPermissionsTo: ChatModeratorLevel = ChatModeratorLevel.None,
  @Json val nameTo: String = "",
  @Json val rangTo: Int = 0,
  @Json val system: Boolean = false,
  @Json val yellow: Boolean = false,
  @Json val sourceUserPremium: Boolean = false,
  @Json val targetUserPremium: Boolean = false
)

data class BattleChatMessage(
  @Json val nickname: String,
  @Json val rank: Int,
  @Json val chat_level: ChatModeratorLevel = ChatModeratorLevel.None,
  @Json val message: String,
  @Json val team_type: BattleTeam,
  @Json val system: Boolean = false,
  @Json val team: Boolean
)

enum class ChatModeratorLevel(val key: Int) {
  None(0),
  Candidate(1),
  Moderator(2),
  Administrator(3),
  CommunityManager(4);

  companion object {
    private val map = values().associateBy(ChatModeratorLevel::key)

    fun get(key: Int) = map[key]
  }
}

data class InitChatMessagesData(
  @Json val messages: List<ChatMessage>,
  @Json val news: List<ServerNewsData>
)

data class InitChatSettings(
  @Json val antiFloodEnabled: Boolean = true,
  @Json val typingSpeedAntifloodEnabled: Boolean = true,
  @Json val bufferSize: Int = 60,
  @Json val minChar: Int = 60,
  @Json val minWord: Int = 5,
  @Json val showLinks: Boolean = true,
  @Json val admin: Boolean = false,
  @Json val selfName: String,
  @Json val chatModeratorLevel: Int = 0,
  @Json val symbolCost: Int = 176,
  @Json val enterCost: Int = 880,
  @Json val chatEnabled: Boolean = true,
  @Json val linksWhiteList: List<String> = listOf("http://gtanks-online.com/", "http://vk.com/ebal")
)

data class AuthData(
  @Json val captcha: String,
  @Json val remember: Boolean,
  @Json val login: String,
  @Json val password: String
)

data class InitRegistrationModelData(
  @Json val bgResource: Int = 122842,
  @Json val enableRequiredEmail: Boolean = false,
  @Json val maxPasswordLength: Int = 100,
  @Json val minPasswordLength: Int = 6
)

data class InitPremiumData(
  @Json val left_time: Int = -1,
  @Json val needShowNotificationCompletionPremium: Boolean = false,
  @Json val needShowWelcomeAlert: Boolean = false,
  @Json val reminderCompletionPremiumTime: Int = 86400,
  @Json val wasShowAlertForFirstPurchasePremium: Boolean = false,
  @Json val wasShowReminderCompletionPremium: Boolean = true
)

data class InitPanelData(
  @Json val name: String,
  @Json val crystall: Int,
  @Json val email: String? = null,
  @Json val tester: Boolean = false,
  @Json val next_score: Int,
  @Json val place: Int = 0,
  @Json val rang: Int,
  @Json val rating: Int = 1,
  @Json val score: Int,
  @Json val currentRankScore: Int,
  @Json val hasDoubleCrystal: Boolean = false,
  @Json val durationCrystalAbonement: Int = -1,
  @Json val userProfileUrl: String = ""
)

data class FriendEntry(
  @Json val id: String,
  @Json val rank: Int,
  @Json val online: Boolean
)

data class InitFriendsListData(
  @Json val friends: List<FriendEntry> = listOf(),
  @Json val incoming: List<FriendEntry> = listOf(),
  @Json val outcoming: List<FriendEntry> = listOf(),
  @Json val new_incoming_friends: List<FriendEntry> = listOf(),
  @Json val new_accepted_friends: List<FriendEntry> = listOf()
)

data class ShowSettingsData(
  @Json val emailNotice: Boolean = false,
  @Json val email: String? = null,
  @Json val notificationEnabled: Boolean = true,
  @Json val showDamageEnabled: Boolean = true,
  @Json val isConfirmEmail: Boolean = false,
  @Json val authorizationUrl: String = "http://localhost/",
  @Json val linkExists: Boolean = false,
  @Json val snId: String = "vkontakte",
  @Json val passwordCreated: Boolean = true
)

data class BattleCreateData(
  @Json val withoutCrystals: Boolean,
  @Json val equipmentConstraintsMode: EquipmentConstraintsMode = EquipmentConstraintsMode.None,
  @Json val parkourMode: Boolean = false,
  @Json val minRank: Int,
  @Json(name = "reArmorEnabled") val rearmingEnabled: Boolean,
  @Json val maxPeopleCount: Int,
  @Json val autoBalance: Boolean,
  @Json val maxRank: Int,
  @Json val battleMode: BattleMode,
  @Json val mapId: String,
  @Json val name: String,
  @Json val scoreLimit: Int,
  @Json val friendlyFire: Boolean,
  @Json val withoutBonuses: Boolean,
  @Json val timeLimitInSec: Int,
  @Json val proBattle: Boolean,
  @Json val theme: String,
  @Json val withoutSupplies: Boolean,
  @Json val privateBattle: Boolean
)
