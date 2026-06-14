package flashtanki.server.battles

import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import mu.KotlinLogging
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import flashtanki.server.ISocketServer
import flashtanki.server.battles.bonus.BattleBonus
import flashtanki.server.battles.effect.TankEffect
import flashtanki.server.battles.effect.toTankEffectData
import flashtanki.server.battles.map.IMapRegistry
import flashtanki.server.battles.map.getSkybox
import flashtanki.server.battles.mode.*
import flashtanki.server.battles.weapons.*
import flashtanki.server.client.*
import flashtanki.server.commands.Command
import flashtanki.server.commands.CommandName
import flashtanki.server.extensions.singleOrNullOf
import flashtanki.server.garage.ServerGarageUserItem
import flashtanki.server.garage.ServerGarageUserItemSupply
import flashtanki.server.math.Quaternion
import flashtanki.server.math.Vector3

object BattlePlayerConstants {
  const val USER_INIT_SEQUENCE: Long = 1
  const val SPECTATOR_INIT_SEQUENCE: Long = 2
}

class BattlePlayer(
  coroutineContext: CoroutineContext,
  val socket: UserSocket,
  val battle: Battle,
  var team: BattleTeam,
  var tank: BattleTank? = null,
  val isSpectator: Boolean = false,
  var score: Int = 0,
  var kills: Int = 0,
  var deaths: Int = 0
) : KoinComponent {
  private val logger = KotlinLogging.logger { }

  private val mapRegistry: IMapRegistry by inject()
  private val server: ISocketServer by inject()
  
  var ultimateCharge: Int = 0

  var sequence: Long = 0
  var incarnation: Int = 0

  val ready: Boolean
    get() {
      return if(isSpectator) sequence >= BattlePlayerConstants.SPECTATOR_INIT_SEQUENCE
      else sequence >= BattlePlayerConstants.USER_INIT_SEQUENCE
    }

  val user: User
    get() = socket.user ?: throw Exception("Missing User")

  val coroutineScope = CoroutineScope(coroutineContext + SupervisorJob())

  var initSpectatorUserCalled: Boolean = false

  var equipmentChanged: Boolean = false

  var isActive: Boolean = false

  suspend fun deactivate(terminate: Boolean = false) {
    tank?.deactivate(terminate)
    coroutineScope.cancel()

    battle.modeHandler.playerLeave(this)
    if(!isSpectator) {
      Command(CommandName.BattlePlayerRemove, user.username).sendTo(battle, exclude = this)

      when(battle.modeHandler) {
        is DeathmatchModeHandler -> Command(CommandName.ReleaseSlotDm, battle.id, user.username)
		is JuggernautModeHandler -> Command(CommandName.ReleaseSlotDm, battle.id, user.username)
        is TeamModeHandler       -> Command(CommandName.ReleaseSlotTeam, battle.id, user.username)
        else                     -> throw IllegalStateException("Unknown battle mode: ${battle.modeHandler::class}")
      }.let { command ->
        server.players
          .filter { player -> player.screen == Screen.BattleSelect }
          .filter { player -> player.active }
          .forEach { player -> command.send(player) }
      }

      Command(
        CommandName.NotifyPlayerLeaveBattle,
        NotifyPlayerJoinBattleData(
          userId = user.username,
          battleId = battle.id,
          mapName = battle.title,
          mode = battle.modeHandler.mode,
          privateBattle = battle.properties[BattleProperty.privateBattle],
          proBattle = battle.properties[BattleProperty.ProBattle],
          minRank = battle.properties[BattleProperty.MinRank],
          maxRank = battle.properties[BattleProperty.MaxRank]
        ).toJson()
      ).let { command ->
        server.players
          .filter { player -> player.screen == Screen.BattleSelect }
          .filter { player -> player.active }
          .forEach { player -> command.send(player) }
      }

      Command(
        CommandName.RemoveBattlePlayer,
        battle.id,
        user.username
      ).let { command ->
        server.players
          .filter { player -> player.screen == Screen.BattleSelect && player.selectedBattle == battle }
          .filter { player -> player.active }
          .forEach { player -> command.send(player) }
      }
      isActive = false
      //Command(CommandName.AddUltimateCharge, ("-" + ultimateCharge).toString()).send(socket)
      //ultimateCharge = 0
    }
  }

  suspend fun init() {
    Command(
      CommandName.InitBonusesData,
      InitBonusesDataData(
        bonuses = listOf(
          BonusData(
            lighting = BonusLightingData(color = 6250335),
            id = "nitro",
            resourceId = 170010
          ),
          BonusData(
            lighting = BonusLightingData(color = 9348154),
            id = "damage",
            resourceId = 170011
          ),
          BonusData(
            lighting = BonusLightingData(color = 7185722),
            id = "armor",
            resourceId = 170006
          ),
          BonusData(
            lighting = BonusLightingData(color = 14605789),
            id = "health",
            resourceId = 170009
          ),
          BonusData(
            lighting = BonusLightingData(color = 15044128),
            id = "gold",
            resourceId = 170008
          ),
		  BonusData(
            lighting = BonusLightingData(color = 15044128),
            id = "container",
            resourceId = 170007
          )
        )
      ).toJson()
    ).send(socket)

    Command(
      CommandName.InitBattleModel,
      InitBattleModelData(
        battleId = battle.id,
        map_id = battle.map.name,
        mapId = battle.map.id,
        spectator = isSpectator,
        reArmorEnabled = battle.properties[BattleProperty.RearmingEnabled],
        minRank = battle.properties[BattleProperty.MinRank],
        maxRank = battle.properties[BattleProperty.MaxRank],
        skybox = mapRegistry.getSkybox(battle.map.skybox)
          .mapValues { (_, resource) -> resource.id }
          .toJson(),
        map_graphic_data = battle.map.visual.toJson()
      ).toJson()
    ).send(socket)
    if (!battle.properties[BattleProperty.WithoutBonuses]) {
      battle.createBonusRegions(socket)
    }
    Command(
      CommandName.InitBonuses,
      battle.bonusProcessor.bonuses.values.map(BattleBonus::toInitBonus).toJson()
    ).send(socket)
  }

  suspend fun initLocal() {
    if(!isSpectator) {
      Command(CommandName.InitStatisticsModel, battle.title).send(socket)
    }
    battle.modeHandler.initModeModel(this)

    Command(
      CommandName.InitGuiModel,
      InitGuiModelData(
        name = battle.title,
        fund = battle.fundProcessor.fund,
        scoreLimit = battle.properties[BattleProperty.ScoreLimit],
        timeLimit = battle.properties[BattleProperty.TimeLimit],
        timeLeft = battle.timeLeft?.inWholeSeconds?.toInt() ?: 0,
        team = team != BattleTeam.None,
        parkourMode = battle.properties[BattleProperty.ParkourMode],
        battleType = battle.modeHandler.mode,
        users = battle.players.users().map { player ->
          GuiUserData(
            nickname = player.user.username,
            rank = player.user.rank.value,
            teamType = player.team
          )
        }
      ).toJson()
    ).send(socket)

    battle.modeHandler.initPostGui(this)
  }

  suspend fun initBattle() {
    if(isSpectator) {
      Command(
        CommandName.UpdateSpectatorsList,
        UpdateSpectatorsListData(
          spects = listOf(user.username)
        ).toJson()
      ).send(this)
    }

    battle.modeHandler.playerJoin(this)

    if(isSpectator || battle.properties[BattleProperty.WithoutSupplies]) {
      Command(
        CommandName.InitInventory,
        InitInventoryData(items = listOf()).toJson()
      ).send(socket)
    } else
      Command(
        CommandName.InitInventory,
        InitInventoryData(
          items = listOf(
            InventoryItemData(
              id = "health",
              count = user.items.singleOrNullOf<ServerGarageUserItem, ServerGarageUserItemSupply> { it.id.itemName == "health" }?.count ?: 0,
              slotId = 1,
              itemEffectTime = 20,
              itemRestSec = 20
            ),
            InventoryItemData(
              id = "armor",
              count = user.items.singleOrNullOf<ServerGarageUserItem, ServerGarageUserItemSupply> { it.id.itemName == "armor" }?.count ?: 0,
              slotId = 2,
              itemEffectTime = 55,
              itemRestSec = 20
            ),
            InventoryItemData(
              id = "double_damage",
              count = user.items.singleOrNullOf<ServerGarageUserItem, ServerGarageUserItemSupply> { it.id.itemName == "double_damage" }?.count ?: 0,
              slotId = 3,
              itemEffectTime = 55,
              itemRestSec = 20
            ),
            InventoryItemData(
              id = "n2o",
              count = user.items.singleOrNullOf<ServerGarageUserItem, ServerGarageUserItemSupply> { it.id.itemName == "n2o" }?.count ?: 0,
              slotId = 4,
              itemEffectTime = 55,
              itemRestSec = 20
            ),
            InventoryItemData(
              id = "mine",
              count = user.items.singleOrNullOf<ServerGarageUserItem, ServerGarageUserItemSupply> { it.id.itemName == "mine" }?.count ?: 0,
              slotId = 5,
              itemEffectTime = 20,
              itemRestSec = 20
            ),
			InventoryItemData(
              id = "gold",
              count = user.items.singleOrNullOf<ServerGarageUserItem, ServerGarageUserItemSupply> { it.id.itemName == "gold" }?.count ?: 0,
              slotId = 6,
              itemEffectTime = 1,
              itemRestSec = 5
            )
          )
        ).toJson()
      ).send(socket)
	  
	  /*Command(
	   CommandName.InitUltimateModel
	  ).send(socket) */

    Command(
      CommandName.InitMineModel,
      InitMineModelSettings().toJson(),
      InitMineModelData(
        mines = battle.mineProcessor.mines.values.map(BattleMine::toAddMine)
      ).toJson()
    ).send(socket)
    isActive = true
    // Init self tank to another players
    if(!isSpectator) {
      val tank = tank ?: throw Exception("No Tank")
      tank.initSelf()
    }

    initAnotherTanks()

    if(!isSpectator) {

      logger.info { "Load stage 2 for ${user.username}" }

      updateStats()
    }

    Command(
      CommandName.InitEffects,
      InitEffectsData(
        effects = battle.players.users()
          .mapNotNull { player -> player.tank }
          .flatMap { tank -> tank.effects.map(TankEffect::toTankEffectData) }
      ).toJson()
    ).send(socket)

    val tank = tank
    if(!isSpectator && tank != null) {
      tank.updateSpawnPosition()
      tank.prepareToSpawn()
	  //tank.updateUltimateCharge()
    }

    spawnAnotherTanks()
  }

  suspend fun initAnotherTanks() {
    // Init another tanks to self
    battle.players
      .exclude(this)
      .ready()
      .users()
      .mapNotNull { player -> player.tank }
      .forEach { tank ->
        Command(
          CommandName.InitTank,
          tank.getInitTank().toJson()
        ).send(socket)
      }
  }

  suspend fun spawnAnotherTanks() {
    // Spawn another tanks for self
    battle.players
      .exclude(this)
      .ready()
      .users()
      .mapNotNull { player -> player.tank }
      .forEach { tank ->
        Command(
          CommandName.SpawnTank,
          tank.getSpawnTank().toJson()
        ).send(socket)

        if(isSpectator) {
          when(tank.state) {
            TankState.Active     -> {
              Command(CommandName.ClientActivateTank, tank.id).send(socket)
            }

            // TODO(Assasans)
            TankState.Dead       -> Unit
            TankState.Respawn    -> Unit
            TankState.SemiActive -> Unit
          }
        }
      }
  }

  suspend fun updateStats() {
    val tank = tank ?: throw Exception("No Tank")
    val scoreLimit = battle.properties[BattleProperty.ScoreLimit]

    Command(
      CommandName.UpdatePlayerStatistics,
      UpdatePlayerStatisticsData(
        id = tank.id,
        rank = user.rank.value,
        team_type = team,
        score = score,
        kills = kills,
        deaths = deaths
      ).toJson()
    ).sendTo(battle)

    when {
      scoreLimit != 0 && kills == scoreLimit && (battle.modeHandler is DeathmatchModeHandler || battle.modeHandler is TeamDeathmatchModeHandler) -> {
        logger.debug("Player ${tank.id} scored kills limit.")
        battle.restart()
      }
    }
  }

  suspend fun changeEquipment(silent: Boolean=false) {
    val tank = tank ?: throw Exception("No Tank")

    Command(CommandName.BattlePlayerRemove, user.username).sendTo(battle)
    tank.initSelf()
    if (!silent) {
      Command(CommandName.EquipmentChanged, user.username).sendTo(battle)
    }
  }

  suspend fun createTank(): BattleTank {
    incarnation++

    val tank = BattleTank(
      id = user.username,
      player = this,
      incarnation = incarnation,
      state = TankState.Respawn,
      position = Vector3(0.0, 0.0, 1000.0),
      orientation = Quaternion(),
      hull = user.equipment.hull,
      weapon = when(user.equipment.weapon.id.itemName) {
        "railgun"               -> RailgunWeaponHandler(this, user.equipment.weapon)
        "railgun_xt"            -> Railgun_XTWeaponHandler(this, user.equipment.weapon)
        "railgun_terminator"    -> Railgun_TERMINATORWeaponHandler(this, user.equipment.weapon)
        "thunder"               -> ThunderWeaponHandler(this, user.equipment.weapon)
        "isida"                 -> IsidaWeaponHandler(this, user.equipment.weapon)
        "smoky"                 -> SmokyWeaponHandler(this, user.equipment.weapon)
        "twins"                 -> TwinsWeaponHandler(this, user.equipment.weapon)
        "flamethrower"          -> FlamethrowerWeaponHandler(this, user.equipment.weapon)
        "freeze"                -> FreezeWeaponHandler(this, user.equipment.weapon)
        "ricochet"              -> RicochetWeaponHandler(this, user.equipment.weapon)
        "shaft"                 -> ShaftWeaponHandler(this, user.equipment.weapon)
        else                    -> NullWeaponHandler(this, user.equipment.weapon)
      },
      coloring = user.equipment.paint,
	  resistance = user.equipment.resistance
    )

    this.tank = tank
    return tank
  }

  suspend fun respawn() {
 //   val tank = tank ?: throw Exception("No Tank")
//    tank.updateSpawnPosition()
//    tank.prepareToSpawn()
//=============================================	 
	  //AI gen
 val oldTank = tank
    
    // Create a new tank (increments incarnation)
    val newTank = createTank()
    newTank.position = oldTank?.position ?: Vector3(0.0, 0.0, 1000.0)
    newTank.orientation = oldTank?.orientation ?: Quaternion()
    newTank.health = newTank.hull.modification.maxHealth
    
    // Spawn the new tank
    newTank.spawn()
    
    // Activate it after a delay
    newTank.activate()
	  
  }
}
