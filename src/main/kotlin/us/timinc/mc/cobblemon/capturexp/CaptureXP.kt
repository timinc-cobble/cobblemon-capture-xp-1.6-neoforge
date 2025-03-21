package us.timinc.mc.cobblemon.capturexp

import com.cobblemon.mod.common.Cobblemon
import com.cobblemon.mod.common.api.events.CobblemonEvents
import com.cobblemon.mod.common.api.events.pokemon.PokemonCapturedEvent
import com.cobblemon.mod.common.api.pokemon.experience.SidemodExperienceSource
import com.cobblemon.mod.common.api.tags.CobblemonItemTags
import com.cobblemon.mod.common.pokemon.OriginalTrainerType
import com.cobblemon.mod.common.pokemon.evolution.requirements.LevelRequirement
import com.cobblemon.mod.common.util.isInBattle
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.fml.common.EventBusSubscriber
import net.neoforged.fml.common.Mod
import net.neoforged.neoforge.event.server.ServerStartedEvent
import us.timinc.mc.cobblemon.capturexp.config.CaptureXPConfig
import us.timinc.mc.cobblemon.capturexp.config.ConfigBuilder
import kotlin.math.pow
import kotlin.math.roundToInt

@Mod(CaptureXP.MOD_ID)
object CaptureXP {
    const val MOD_ID = "capture_xp"
    private var captureXPConfig: CaptureXPConfig = ConfigBuilder.load(CaptureXPConfig::class.java, MOD_ID)
    var eventsListening = false

    @EventBusSubscriber()
    object Registration {
        @SubscribeEvent
        fun onInit(e: ServerStartedEvent) {
            if (eventsListening) return
            eventsListening = true
            CobblemonEvents.POKEMON_CAPTURED.subscribe { event ->
                if (event.player.isInBattle()) handleCaptureInBattle(event) else handleCaptureOutOfBattle(event)
            }
        }
    }

    private fun handleCaptureInBattle(event: PokemonCapturedEvent) {
        val battle = Cobblemon.battleRegistry.getBattleByParticipatingPlayer(event.player) ?: return
        val caughtBattleMonActor = battle.actors.find { it.uuid == event.pokemon.uuid } ?: return
        val caughtBattleMon = caughtBattleMonActor.pokemonList.find { it.uuid == event.pokemon.uuid } ?: return

        caughtBattleMonActor.getSide().getOppositeSide().actors.forEach { opponentActor ->
            opponentActor.pokemonList.filter {
                it.health > 0 && (caughtBattleMon.facedOpponents.contains(it) || it.effectedPokemon.heldItem()
                    .`is`(CobblemonItemTags.EXPERIENCE_SHARE))
            }.forEach { opponentMon ->
                val xpShareOnly = !caughtBattleMon.facedOpponents.contains(opponentMon)
                val xpShareOnlyModifier =
                    (if (xpShareOnly) Cobblemon.config.experienceShareMultiplier else 1).toDouble()
                val experience = Cobblemon.experienceCalculator.calculate(
                    opponentMon, caughtBattleMon, captureXPConfig.multiplier * xpShareOnlyModifier
                )
                if (experience > 0) {
                    opponentActor.awardExperience(opponentMon, experience)
                }
            }
        }
    }

    private fun handleCaptureOutOfBattle(event: PokemonCapturedEvent) {
        val opponentPokemon = event.pokemon
        val playerParty = Cobblemon.storage.getParty(event.player)
        val source = SidemodExperienceSource(MOD_ID)
        val first = playerParty.firstOrNull { it != event.pokemon && it.currentHealth > 0 } ?: return
        val playerMons = playerParty.filter {
            it != event.pokemon && it.currentHealth > 0 && (it.uuid == first.uuid || it.heldItem()
                .`is`(CobblemonItemTags.EXPERIENCE_SHARE))
        }
        playerMons.forEach { playerMon ->
            val baseXp = opponentPokemon.form.baseExperienceYield
            val opponentLevel = opponentPokemon.level
            val term1 = (baseXp * opponentLevel) / 5.0

            val xpShareOnly = playerMon.uuid != first.uuid
            val xpShareModifier = Cobblemon.config.experienceShareMultiplier
            val captureModifier = captureXPConfig.multiplier
            val term2 = (if (xpShareOnly) xpShareModifier else 1.0) * captureModifier

            val playerMonLevel = playerMon.level
            val term3 = (((2.0 * opponentLevel) + 10) / (opponentLevel + playerMonLevel + 10)).pow(2.5)

            val term4 = term1 * term2 * term3 + 1

            val isNonOt =
                playerMon.originalTrainerType == OriginalTrainerType.PLAYER && playerMon.originalTrainer != event.player.uuid.toString()
            val nonOtBonus = if (isNonOt) 1.5 else 1.0
            val hasLuckyEgg = playerMon.heldItem().`is`(CobblemonItemTags.LUCKY_EGG)
            val luckyEggBonus = if (hasLuckyEgg) Cobblemon.config.luckyEggMultiplier else 1.0
            val isAffectionate = playerMon.friendship >= 220
            val affectionateBonus = if (isAffectionate) 1.2 else 1.0
            val isCloseToEvolution = playerMon.evolutionProxy.server().any { evolution ->
                val requirements = evolution.requirements.asSequence()
                requirements.any { it is LevelRequirement } && requirements.all { it.check(playerMon) }
            }
            val closeToEvolutionBonus = if (isCloseToEvolution) 1.2 else 1.0

            val cobblemonModifier = Cobblemon.config.experienceMultiplier

            val experience =
                (term4 * nonOtBonus * luckyEggBonus * closeToEvolutionBonus * affectionateBonus * cobblemonModifier).roundToInt()

            playerMon.addExperienceWithPlayer(event.player, source, experience)
        }
    }
}