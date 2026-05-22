package flashtanki.server

import flashtanki.server.api.IApiServer
import flashtanki.server.battles.Battle
import flashtanki.server.battles.BattleProperty
import flashtanki.server.battles.BattleTeam
import flashtanki.server.battles.IBattleProcessor
import flashtanki.server.battles.map.IMapRegistry
import flashtanki.server.battles.map.get
import flashtanki.server.battles.mode.*
// import flashtanki.server.bot.discord.*
import flashtanki.server.chat.*
import flashtanki.server.client.*
import flashtanki.server.commands.Command
import flashtanki.server.commands.CommandName
import flashtanki.server.commands.ICommandHandler
import flashtanki.server.commands.ICommandRegistry
// import flashtanki.server.discord.jda.JDA
import flashtanki.server.extensions.cast
import flashtanki.server.extensions.toString
import flashtanki.server.garage.*
import flashtanki.server.invite.IInviteRepository
import flashtanki.server.invite.IInviteService
import flashtanki.server.ipc.IProcessNetworking
import flashtanki.server.ipc.ServerStartedMessage
import flashtanki.server.ipc.ServerStartingMessage
import flashtanki.server.ipc.send
import flashtanki.server.lobby.chat.ILobbyChatManager
import flashtanki.server.resources.IResourceServer
import flashtanki.server.store.IStoreRegistry
import kotlinx.coroutines.*
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import mu.KotlinLogging
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.reflections.Reflections
import org.reflections.scanners.Scanners
import java.math.BigInteger
import kotlin.coroutines.coroutineContext
import kotlin.reflect.KClass
import kotlin.system.exitProcess
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime

@Suppress("NAME_SHADOWING")
class Server : KoinComponent {
  private val logger = KotlinLogging.logger { }

  private val processNetworking by inject<IProcessNetworking>()
  private val socketServer by inject<ISocketServer>()
  private val resourceServer by inject<IResourceServer>()
  private val apiServer by inject<IApiServer>()
  private val commandRegistry by inject<ICommandRegistry>()
  private val battleProcessor by inject<IBattleProcessor>()
  private val marketRegistry by inject<IGarageMarketRegistry>()
  private val mapRegistry by inject<IMapRegistry>()
  private val chatCommandRegistry by inject<IChatCommandRegistry>()
  private val storeRegistry by inject<IStoreRegistry>()
  private val userRepository by inject<IUserRepository>()
  private val inviteService by inject<IInviteService>()
  private val lobbyChatManager by inject<ILobbyChatManager>()
  private val inviteRepository by inject<IInviteRepository>()
  private val promoCodeService by inject<IPromoCodeService>()

  private var networkingEventsJob: Job? = null

  @OptIn(ExperimentalTime::class)
  suspend fun run() {
    logger.info { "Starting server..." }
    processNetworking.run()
    ServerStartingMessage().send()

    coroutineScope {
      launch { mapRegistry.load() }
      launch { marketRegistry.load() }
      launch { storeRegistry.load() }
    }

    val reflections = Reflections("flashtanki.server")

    reflections.get(Scanners.SubTypes.of(ICommandHandler::class.java).asClass<ICommandHandler>()).forEach { type ->
      val handlerType = type.kotlin.cast<KClass<ICommandHandler>>()

      commandRegistry.registerHandlers(handlerType)
      logger.debug { "Registered command handler: ${handlerType.simpleName}" }
    }
    val battle = Battle(
            coroutineContext,
            id = Battle.generateId(),
            title = "For Newbies JGR Mode",
            map = mapRegistry.get("map_sandbox", ServerMapTheme.SummerDay),
            modeHandlerBuilder = JuggernautModeHandler.builder()
    )

    battle.properties[BattleProperty.TimeLimit] = 900
    battle.properties[BattleProperty.MaxPeople] = 8
    battle.properties[BattleProperty.MaxRank] = UserRank.Generalissimo.value

    battleProcessor.battles.add(battle)
    promoCodeService.initPromoCodes()
    chatCommandRegistry.apply {
      command("help") {
        permissions(Permissions.Superuser.toBitfield())
        description("Show list of commands or help for a specific command")

        argument("command", String::class) {
          permissions(Permissions.Superuser.toBitfield())
          description("Command to show help for")
          optional()
        }

        handler {
          val commandName: String? = arguments.getOrNull("command")
          val user = socket.user ?: throw Exception("User is null")
          if(commandName == null) {
            val availableCommands = commands.filter { it.permissions.any (user.permissions) }
            val commandList = availableCommands.joinToString(", \n") { it.name }
            reply("Available commands (${availableCommands.size}):\n$commandList")
            return@handler
          }

          val command = commands.singleOrNull { command -> command.name == commandName }
          if(command == null || !command.permissions.any(user.permissions)) {
            reply("Unknown command: $commandName")
            return@handler
          }

          val builder: StringBuilder = StringBuilder()

          builder.append(command.name)
          if(command.description != null) {
            builder.append(" - ${command.description}")
          }
          builder.append("\n")

          if(command.arguments.isNotEmpty()) {
            builder.appendLine("Arguments:")
            command.arguments.forEach { argument ->
              builder.append("    ")
              builder.append("${argument.name}: ${argument.type.simpleName}")
              if(argument.isOptional) builder.append(" (optional)")
              if(argument.description != null) {
                builder.append(" - ${argument.description}")
              }
              builder.appendLine()
            }
          }

          reply(builder.toString())
        }
      }

      command("online") {
        permissions(Permissions.Moderator.toBitfield().plus(Permissions.Superuser.toBitfield()))
        description("Shows the number of online players")

        handler {
          val onlinePlayersCount = socketServer.players.size
          reply("Online players: $onlinePlayersCount")
        }
      }

      command("addpermission") {
        permissions(Permissions.Superuser.toBitfield())
        description("Добавить разрешения пользователю")
        alias("addperms")

        argument("permissions", String::class) {
          description("Количество разрешений для добавления")
        }

        argument("user", String::class) {
          description("Имя пользователя для добавления разрешений")
          optional()
        }

        handler {
          val permissionsToAddString: String? = arguments["permissions"]
          val permissionsToAdd: Int = (permissionsToAddString?.toIntOrNull() ?: Permissions.DefaultUser.toBitfield()) as Int
          val username: String = arguments["user"]

          val targetUser = userRepository.getUser(username)
          if (targetUser == null) {
            reply("Пользователь не найден: $username")
            return@handler
          }

          val permissions = Bitfield<Permissions>(permissionsToAdd.toLong())
          targetUser.permissions.plusAssign(permissions)
          userRepository.updateUser(targetUser)

          val addedPermissionsNames = Permissions.values()
                  .filter { permissions has it }
                  .joinToString(", ") { it.name }

          reply("Успешно добавлены разрешения: $addedPermissionsNames пользователю: ${targetUser.username}")
        }
      }

      command("kick") {
        permissions(Permissions.Moderator.toBitfield().plus(Permissions.Superuser.toBitfield()))
        description("Kick a user from the server")

        argument("user", String::class) {
          permissions(Permissions.Moderator.toBitfield().plus(Permissions.Superuser.toBitfield()))
          description("The user to kick")
        }

        handler {
          val username: String = arguments["user"]
          val player = socketServer.players.singleOrNull { socket -> socket.user?.username == username }
          if(player == null) {
            reply("User '$username' not found")
            return@handler
          }

          player.deactivate()
          if(player != socket) {
            reply("User '$username' has been kicked")
          }
        }
      }

      command("dump") {
        permissions(Permissions.Superuser.toBitfield())
        subcommand("battle") {
          permissions(Permissions.Superuser.toBitfield())
          handler {
            val battle = socket.battle
            if(battle == null) {
              reply("You are not in a battle")
              return@handler
            }

            val builder = StringBuilder()

            builder.appendLine("Battle:")
            builder.appendLine("    ID: ${battle.id}")
            builder.appendLine("    Name: ${battle.title}")
            builder.appendLine("Map:")
            builder.appendLine("    ID: ${battle.map.id}")
            builder.appendLine("    Name: ${battle.map.name}")
            builder.appendLine("    Theme: ${battle.map.theme.name}")
            builder.appendLine("Players:")
            battle.players.forEach { player ->
              builder.append("    - ${player.user.username}")

              val properties = mutableListOf<String>()

              properties.add("sequence: ${player.sequence}")

              if(player.team != BattleTeam.None) {
                properties.add("team: ${player.team.name}")
              }
              properties.add("score: ${player.score}")
              properties.add("kills: ${player.kills}")
              properties.add("deaths: ${player.deaths}")

              if(properties.isNotEmpty()) {
                builder.append(" (${properties.joinToString(", ")})")
              }
              builder.append("\n")

              val tank = player.tank
              if(tank != null) {
                builder.appendLine("        Tank: ${tank.id}/${tank.incarnation} (${tank.state})")
                builder.appendLine("            Position: ${tank.position}")
                builder.appendLine("            Orientation: ${tank.orientation}")
              }
            }
            builder.appendLine("Handler:")
            builder.appendLine("    Class: ${battle.modeHandler::class.simpleName}")
            builder.appendLine("    Mode: ${battle.modeHandler.mode.name}")
            battle.modeHandler.dump(builder)

            reply(builder.toString())
          }
        }
      }

      command("finish") {
        permissions(Permissions.Moderator.toBitfield().plus(Permissions.Superuser.toBitfield()))
        description("Finish and restart the current battle")

        handler {
          val battle = socket.battle
          if(battle == null) {
            reply("You are not in a battle")
            return@handler
          }

          battle.restart()
          reply("Battle finished")
        }
      }

      command("property") {
        permissions(Permissions.Superuser.toBitfield())
        subcommand("list") {
          permissions(Permissions.Superuser.toBitfield())
          handler {
            val battle = socket.battle
            if(battle == null) {
              reply("You are not in a battle")
              return@handler
            }

            val builder = StringBuilder()
            BattleProperty.values().forEach { property ->
              val value = battle.properties[property]

              builder.append("${property.key}: $value")
              if(property.defaultValue != null) {
                builder.append(" (default: ${property.defaultValue})")
              }
              builder.append("\n")
            }

            reply(builder.toString())
          }
        }

        subcommand("get") {
          permissions(Permissions.Superuser.toBitfield())
          argument("property", String::class) {
            description("The battle property key to get")
          }

          handler {
            val key: String = arguments["property"]

            val battle = socket.battle
            if(battle == null) {
              reply("You are not in a battle")
              return@handler
            }

            val builder = StringBuilder()

            val property = BattleProperty.getOrNull(key)
            if(property == null) {
              reply("No such property: $key")
              return@handler
            }

            val value = battle.properties[property]
            builder.append("${property.key}: $value")
            if(property.defaultValue != null) {
              builder.append(" (default: ${property.defaultValue})")
            }

            reply(builder.toString())
          }
        }

        subcommand("set") {
          permissions(Permissions.Superuser.toBitfield())
          argument("property", String::class) {
            description("The battle property key to set")
          }

          argument("value", String::class) {
            permissions(Permissions.Superuser.toBitfield())
            description("The value to set the property to")
          }

          handler {
            val key: String = arguments["property"]
            val value: String = arguments["value"]

            val battle = socket.battle
            if(battle == null) {
              reply("You are not in a battle")
              return@handler
            }

            val builder = StringBuilder()

            val property = BattleProperty.getOrNull(key)
            if(property == null) {
              reply("No such property: $key")
              return@handler
            }

            val oldValue = battle.properties[property]

            val typedValue: Any = when(property.type) {
              String::class  -> value
              Int::class     -> value.toInt()
              Double::class  -> value.toDouble()
              Boolean::class -> when {
                value.equals("false", ignoreCase = true) -> false
                value.equals("true", ignoreCase = true)  -> true
                else                                     -> throw Exception("Invalid Boolean value: $value")
              }

              else           -> throw Exception("Unsupported property type: ${property.type.qualifiedName}")
            }

            battle.properties.setValue(property, typedValue)
            builder.append("Changed $key: $oldValue -> $typedValue")

            reply(builder.toString())
          }
        }
      }

	  command("vote") {
        permissions(Permissions.DefaultUser.toBitfield())
		permissions(Permissions.Superuser.toBitfield())
        description("VoteUser")

          argument("user", String::class) {
            permissions(Permissions.DefaultUser.toBitfield())
			permissions(Permissions.Superuser.toBitfield())
            description("The user")
          }

          handler {
            val username: String? = arguments.getOrNull("user")

            val battle = socket.battle
            if(battle == null) {
              reply("Во время выполнения команды произошла ошибка")//reply("You are not in a battle")
              return@handler
            }

            val player = if(username != null) socketServer.players.find { it.user?.username == username } else socket
            if(player == null) {
              reply("Player not found: $username")
              return@handler
            }
            Command(CommandName.ConfirmVote).send(socket)
			reply("Жалоба на нарушителя успешно отправлена")
            val user = player.user ?: throw Exception("User is null")
          }
        }

	  command("creategold") {
        permissions(Permissions.Superuser.toBitfield())
        description("Drop gold")

        argument("amount", Int::class) {
          permissions(Permissions.Superuser.toBitfield())
          description("The amount of golds")
        }

		handler {
		   val battle = socket.battle
           if(battle == null) {
             reply("You are not in a battle")
             return@handler
           }
           val amount: Int = arguments.get<String>("amount").toInt()
           var i: Int = 1
           while (i <= amount) {
             battle.spawnGoldBonus();
             i++
           }
		}
	  }
      command("addCL") {
        permissions(Permissions.Superuser.toBitfield())
        description("Add CL")

        argument("user", String::class) {
          permissions(Permissions.Superuser.toBitfield())
          description("The user to add CL")
          optional()
        }

        handler {
          // TODO(TitanoMachina aka KAPJIC0N)
          val username: String? = arguments.getOrNull("user")

          val player = if(username != null) socketServer.players.find { it.user?.username == username } else socket
          if(player == null) {
            reply("Player not found: $username")
            return@handler
          }
          Command(CommandName.AddClanLicense).send(player)
        }
      }
      command("addpremium") {
        permissions(Permissions.Superuser.toBitfield())
        description("Add premium account to a user")
        alias("addprem")

        argument("amount", Int::class) {
          permissions(Permissions.Superuser.toBitfield())
          description("The amount of premium account to add")
        }

        argument("user", String::class) {
          permissions(Permissions.Superuser.toBitfield())
          description("The user to add premium account")
          optional()
        }

        handler {
          //val amount: BigInteger = arguments.get<String>("amount").toBigInteger() // TODO(KAPJIC0N)
          val username: String? = arguments.getOrNull("user")

          /*var intAmount =
            if (amount > Int.MAX_VALUE.toBigInteger()) Int.MAX_VALUE
            else if (amount < 0.toBigInteger()) 0
            else amount.toInt()*/

          val player = if(username != null) socketServer.players.find { it.user?.username == username } else socket
          if(player == null) {
            reply("Player not found: $username")
            return@handler
          }

          val user = player.user ?: throw Exception("User is null")
          val amount: Int = arguments.get<String>("amount").toInt()
          socket.addPremiumAccount(amount)
          userRepository.updateUser(user)

          reply("Added ${amount} day premium account to ${user.username}")
        }
      }

      command("addxp") {
        permissions(Permissions.Superuser.toBitfield())
        description("Add or subtract experience points from a user")
        alias("addscore")

        argument("amount", String::class) {
          permissions(Permissions.Superuser.toBitfield())
          description("The amount of experience points to add (use '-' to subtract)")
        }

        argument("user", String::class) {
          permissions(Permissions.Superuser.toBitfield())
          description("The user to add or subtract experience points")
          optional()
        }

        handler {
          val amountString: String = arguments.get<String>("amount")
          val isSubtraction = amountString.startsWith('-')

          val amount: BigInteger = amountString.replace("-", "").toBigInteger()
          val username: String? = arguments.getOrNull("user")

          var intAmount =
                  if (amount > Int.MAX_VALUE.toBigInteger()) Int.MAX_VALUE
                  else if (amount < 0.toBigInteger()) 0
                  else amount.toInt()

          intAmount = if (isSubtraction) -intAmount else intAmount

          val player = if (username != null) socketServer.players.find { it.user?.username == username } else socket
          if (player == null) {
            reply("Player not found: $username")
            return@handler
          }

          val user = player.user ?: throw Exception("User is null")

          user.score = (user.score + intAmount).coerceAtLeast(0)
          player.updateScore()
          userRepository.updateUser(user)

          val action = if (isSubtraction) "Subtracted" else "Added"
          reply("$action $amount experience points to ${user.username}")
        }
      }

      command("addcry") {
        permissions(Permissions.Superuser.toBitfield())
        description("Add or subtract crystals from a user")

        argument("amount", String::class) {
          permissions(Permissions.Superuser.toBitfield())
          description("The amount of crystals to add (use '-' to subtract)")
        }

        argument("user", String::class) {
          permissions(Permissions.Superuser.toBitfield())
          description("The user to add or subtract crystals")
          optional()
        }

        handler {
          val amountString: String = arguments.get<String>("amount")
          val isSubtraction = amountString.startsWith('-')

          val amount: BigInteger = amountString.replace("-", "").toBigInteger()
          val username: String? = arguments.getOrNull("user")

          var intAmount =
                  if (amount > Int.MAX_VALUE.toBigInteger()) Int.MAX_VALUE
                  else if (amount < 0.toBigInteger()) 0
                  else amount.toInt()

          intAmount = if (isSubtraction) -intAmount else intAmount

          val player = if (username != null) socketServer.players.find { it.user?.username == username } else socket
          if (player == null) {
            reply("Player not found: $username")
            return@handler
          }

          val user = player.user ?: throw Exception("User is null")

          user.crystals = (user.crystals + intAmount).coerceAtLeast(0)
          player.updateCrystals()
          userRepository.updateUser(user)

          val action = if (isSubtraction) "Subtracted" else "Added"
          reply("$action $amount crystals to ${user.username}")
        }
      }

      command("stop") {
        permissions(Permissions.Superuser.toBitfield())
        description("Перезапуск сервера")

        handler {
          reply("Остановка сервера через 50 секунд...")

          scheduleServerStop()
        }
      }

      command("reset-items") {
        permissions(Permissions.Superuser.toBitfield())
        description("Reset all garage items")

        argument("user", String::class) {
          permissions(Permissions.Superuser.toBitfield())
          description("The user to reset items")
          optional()
        }

        handler {
          val username = arguments.getOrNull<String>("user")
          val user: User? = if(username != null) {
            userRepository.getUser(username)
          } else {
            socket.user ?: throw Exception("User is null")
          }
          if(user == null) {
            reply("User '$username' not found")
            return@handler
          }

          HibernateUtils.createEntityManager().let { entityManager ->
            entityManager.transaction.begin()

            user.items.clear()
            user.items += listOf(
              ServerGarageUserItemWeapon(user, "smoky", modificationIndex = 0),
              ServerGarageUserItemHull(user, "hunter", modificationIndex = 0),
              ServerGarageUserItemPaint(user, "green"),
			  ServerGarageUserItemResistance(user, "zero")
            )
            user.equipment.hullId = "hunter"
            user.equipment.weaponId = "smoky"
            user.equipment.paintId = "green"
			user.equipment.resistanceId = "zero"

            withContext(Dispatchers.IO) {
              entityManager
                .createQuery("DELETE FROM ServerGarageUserItem WHERE id.user = :user")
                .setParameter("user", user)
                .executeUpdate()
            }

            withContext(Dispatchers.IO) {
              entityManager
                .createQuery("UPDATE User SET equipment = :equipment WHERE id = :id")
                .setParameter("equipment", user.equipment)
                .setParameter("id", user.id)
                .executeUpdate()
            }

            user.items.forEach { item -> entityManager.persist(item) }

            entityManager.transaction.commit()
            entityManager.close()
          }

          socketServer.players.singleOrNull { player -> player.user?.id == user.id }?.let { target ->
            if(target.screen == Screen.Garage) {
              // Refresh garage to update items
              Command(CommandName.UnloadGarage).send(target)

              target.loadGarageResources()
              target.initGarage()
            }
          }

          reply("Reset garage items for user '${user.username}'")
        }
      }

      command("addfund") {
        permissions(Permissions.Superuser.toBitfield())
        description("Add or subtract fund crystals to a battle")

        argument("amount", String::class) {
          permissions(Permissions.Superuser.toBitfield())
          description("The amount of fund crystals to add (use '-' to subtract)")
        }

        argument("battle", String::class) {
          permissions(Permissions.Superuser.toBitfield())
          description("The battle ID to add or subtract crystals")
          optional()
        }

        handler {
          val amountString: String = arguments.get<String>("amount")
          val isSubtraction = amountString.startsWith('-')

          val amount: BigInteger = amountString.replace("-", "").toBigInteger()
          val battleId: String? = arguments.getOrNull("battle")

          var intAmount =
                  if (amount > Int.MAX_VALUE.toBigInteger()) Int.MAX_VALUE
                  else if (amount < 0.toBigInteger()) 0
                  else amount.toInt()

          intAmount = if (isSubtraction) -intAmount else intAmount

          val battle = if (battleId != null) battleProcessor.battles.singleOrNull { it.id == battleId } else socket.battle
          if (battle == null) {
            if (battleId != null) reply("Battle '$battleId' not found")
            else reply("You are not in a battle")

            return@handler
          }

          battle.fundProcessor.fund = (battle.fundProcessor.fund + intAmount).coerceAtLeast(0)
          battle.fundProcessor.updateFund()

          val action = if (isSubtraction) "Subtracted" else "Added"
          reply("$action $amount fund crystals to battle ${battle.id}")
        }
      }

      command("invite") {
        permissions(Permissions.Superuser.toBitfield())
        description("Manage invites")

        subcommand("toggle") {
          permissions(Permissions.Superuser.toBitfield())
          description("Toggle invite-only mode")

          argument("enabled", Boolean::class) {
            permissions(Permissions.Superuser.toBitfield())
            description("Invite-only mode enabled")
          }

          handler {
            val enabled = arguments.get<String>("enabled").toBooleanStrict()

            inviteService.enabled = enabled

            reply("Invite codes are now ${if(enabled) "required" else "not required"} to log in")
          }
        }

        subcommand("add") {
          permissions(Permissions.Superuser.toBitfield())
          description("Add new invite")

          argument("code", String::class) {
            permissions(Permissions.Superuser.toBitfield())
            description("Invite code to add")
          }

          handler {
            val code = arguments.get<String>("code")

            val invite = inviteRepository.createInvite(code)
            if(invite == null) {
              reply("Invite '$code' already exists")
              return@handler
            }

            reply("Added invite '${invite.code}' (ID: ${invite.id})")
          }
        }

        subcommand("delete") {
          permissions(Permissions.Superuser.toBitfield())
          description("Delete invite")

          argument("code", String::class) {
            permissions(Permissions.Superuser.toBitfield())
            description("Invite code to delete")
          }

          handler {
            val code = arguments.get<String>("code")

            if(!inviteRepository.deleteInvite(code)) {
              reply("Invite '$code' does not exist")
            }

            reply("Deleted invite '$code'")
          }
        }

        subcommand("list") {
          permissions(Permissions.Superuser.toBitfield())
          description("List existing invites")

          handler {
            val invites = inviteRepository.getInvites()
            if(invites.isEmpty()) {
              reply("No invites are available")
              return@handler
            }

            reply("Invites:\n${invites.joinToString("\n") { invite -> "  - ${invite.code} (ID: ${invite.id})" }}")
          }
        }
      }

      command("ban") {
        permissions(Permissions.Moderator.toBitfield().plus(Permissions.Superuser.toBitfield()))
        description("Ban a user")

        argument("user", String::class) {
          permissions(Permissions.Moderator.toBitfield().plus(Permissions.Superuser.toBitfield()))
          description("The user to ban")
        }

        argument("duration", String::class) {
          permissions(Permissions.Moderator.toBitfield().plus(Permissions.Superuser.toBitfield()))
          description("Measured in minutes (ex: 10m), hours (ex: 3h), or days (ex: 14d). If not specified, the player is banned forever.")
          optional()
        }

        argument("reason", String::class) {
          permissions(Permissions.Moderator.toBitfield().plus(Permissions.Superuser.toBitfield()))
          description("Reason for ban")
          optional()
        }

        handler {
          val username: String = arguments["user"]
          val reason: String? = arguments.getOrNull("reason")
          val rawDuration: String? = arguments.getOrNull("duration")

          val player = socketServer.players.singleOrNull { socket -> socket.user?.username == username }

          if(player == null) {
            reply("User '$username' not found")
            return@handler
          }

          if(player.user?.permissions?.has(Permissions.Superuser) == true) {
            reply("Administrator can not be banned")
            return@handler
          }

          val duration = rawDuration?.let { Duration.parseOrNull(it) }

          if (duration == null && rawDuration != null) {
            reply("Duration $rawDuration is invalid")
            return@handler
          }

          if (duration?.isPositive() == false) {
            reply("Duration must be positive")
            return@handler
          }

          val until = if (duration != null) Clock.System.now() + duration else Instant.DISTANT_FUTURE

          player.ban(until, reason)
          userRepository.updateUser(player.user ?: throw Exception("User is null"))

          reply("Player '$username' has been banned ${if (until == Instant.DISTANT_FUTURE) "forever" else "until ${until.toString { "$dd.$MM.$yyyy $HH:$mm:$ss" }}"} ${if (reason == null) "without reason" else "with reason '$reason'"}")
        }
      }

      command("mute") {
        permissions(Permissions.Moderator.toBitfield().plus(Permissions.Superuser.toBitfield()))
        description("Mute a user in the chat")

        argument("user", String::class) {
          permissions(Permissions.Moderator.toBitfield().plus(Permissions.Superuser.toBitfield()))
          description("The user to mute")
        }

        argument("duration", String::class) {
          permissions(Permissions.Moderator.toBitfield().plus(Permissions.Superuser.toBitfield()))
          description("Measured in minutes (ex: 10m), hours (ex: 3h), or days (ex: 14d). If not specified, the player is muted forever.")
          optional()
        }

        argument("reason", String::class) {
          permissions(Permissions.Moderator.toBitfield().plus(Permissions.Superuser.toBitfield()))
          description("Reason for mute")
          optional()
        }

        handler {
          val username: String = arguments["user"]
          val reason: String? = arguments.getOrNull("reason")
          val rawDuration: String? = arguments.getOrNull("duration")

          val player = socketServer.players.singleOrNull { socket -> socket.user?.username == username }

          if(player == null) {
            reply("User '$username' not found")
            return@handler
          }

          if(player.user?.permissions?.has(Permissions.Superuser) == true) {
            reply("Administrator can not be muted")
            return@handler
          }

          val duration = rawDuration?.let { Duration.parseOrNull(it) }

          if (duration == null && rawDuration != null) {
            reply("Duration $rawDuration is invalid")
            return@handler
          }

          if (duration?.isPositive() == false) {
            reply("Duration must be positive")
            return@handler
          }

          val until = if (duration != null) Clock.System.now() + duration else Instant.DISTANT_FUTURE

          player.user?.mute(until, reason)
          userRepository.updateUser(player.user ?: throw Exception("User is null"))

          reply("Player '$username' has been muted ${if (until == Instant.DISTANT_FUTURE) "forever" else "until ${until.toString { "$dd.$MM.$yyyy $HH:$mm:$ss" }}"} ${if (reason == null) "without reason" else "with reason '$reason'"}")
        }
      }

      command("unban") {
        permissions(Permissions.Moderator.toBitfield().plus(Permissions.Superuser.toBitfield()))
        description("Unban a user")

        argument("user", String::class) {
          permissions(Permissions.Moderator.toBitfield().plus(Permissions.Superuser.toBitfield()))
          description("The user to unban")
        }

        handler {
          val username: String = arguments["user"]

          val player = socketServer.players.singleOrNull { socket -> socket.user?.username == username }

          if(player == null) {
            reply("User '$username' not found")
            return@handler
          }

          player.user?.unban()
          userRepository.updateUser(player.user ?: throw Exception("User is null"))
          reply("Player '$username' has been unbanned")
        }
      }

      command("unmute") {
        permissions(Permissions.Moderator.toBitfield().plus(Permissions.Superuser.toBitfield()))
        description("Unmute a user")

        argument("user", String::class) {
          permissions(Permissions.Moderator.toBitfield().plus(Permissions.Superuser.toBitfield()))
          description("The user to unmute")
        }

        handler {
          val username: String = arguments["user"]

          val player = socketServer.players.singleOrNull { socket -> socket.user?.username == username }

          if(player == null) {
            reply("User '$username' not found")
            return@handler
          }

          player.user?.unmute()
          userRepository.updateUser(player.user ?: throw Exception("User is null"))
          reply("Player '$username' has been unmuted")
        }
      }
    }

    HibernateUtils.createEntityManager().close()

    runBlocking {
      coroutineScope {

        socketServer.run(this)
        launch { OAuthService().init() }
        launch { resourceServer.run() }
        launch { apiServer.run() }
        launch { JDA("MTI0OTI4Mjc2ODc4NjAzMDY1Mw.Gu5v7i.PoyQ4vYXlzieG1lhxKsOblen9fm6-g6cNcAZWM") }

        ServerStartedMessage().send()
        logger.info("Server started...")

        launch {
          while (isActive) {
            logger.info("The server will be restarted in 24 hours...")
            delay(24.hours)
            scheduleServerStop()
          }
        }
      }
    }
  }

  @OptIn(ExperimentalTime::class)
  suspend fun scheduleServerStop() {
    runBlocking {
      logger.info("Server stops after 50 seconds...")

      Command(CommandName.ShowServerStop).let {
          command -> socketServer.players.forEach {
          player -> player.send(command) }
      }

      delay(40.seconds)
      logger.info("Restart all battles...")
      battleProcessor.battles.forEach {
        launch { it.restart() }
      }

      delay(10.seconds)
      logger.info("Server stopped...")
      exitProcess(0)
    }
  }
}
