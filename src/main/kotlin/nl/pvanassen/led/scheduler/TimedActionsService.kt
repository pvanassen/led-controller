package nl.pvanassen.led.scheduler

import com.ucasoft.kcron.KCron
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.*
import nl.pvanassen.led.animation.AnimationClients
import nl.pvanassen.led.animation.AnimationLoader
import nl.pvanassen.led.model.TreeState
import nl.pvanassen.led.power.TasmotaClient
import org.slf4j.LoggerFactory
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.hours

class TimedActionsService(private val tasmotaClient: TasmotaClient,
                          private val animationLoader: AnimationLoader,
                          private val animationClients: AnimationClients) {

    private val logger = LoggerFactory.getLogger(this.javaClass)

    private val executor = Executors.newScheduledThreadPool(1)

    private val wakePowerCron = "0 55 6 ? * * *"

    private val fullOn = "0 5 7 ? * * *"

    private val shuttingDownCron = "0 0 23 ? * * *"

    private val shutdownCron = "0 5 23 ? * * *"

    private val fireworkCron = "0 59 23 31 12 * *"

    fun start() {
        executor.scheduleWithFixedDelay({ runBlocking { wakePower() } }, nextRunInMinutes(wakePowerCron), 24.hours.inWholeMinutes, TimeUnit.MINUTES)
        executor.scheduleWithFixedDelay({ forceOn() }, nextRunInMinutes(fullOn), 24.hours.inWholeMinutes, TimeUnit.MINUTES)
        executor.scheduleWithFixedDelay({ shuttingDown() }, nextRunInMinutes(shuttingDownCron), 24.hours.inWholeMinutes, TimeUnit.MINUTES)
        executor.scheduleWithFixedDelay({ shutdownInternal() }, nextRunInMinutes(shutdownCron), 24.hours.inWholeMinutes, TimeUnit.MINUTES)
        executor.scheduleAtFixedRate({ loadTimedTasks() }, 0, 1, TimeUnit.MINUTES)
        executor.schedule({ fireworks() }, nextRunInMinutes(fireworkCron), TimeUnit.MINUTES)
    }

    private fun loadTimedTasks() {
        val nameCronMap = animationClients.removeNameCronEntries()
        nameCronMap.forEach { (name, cron) ->
            try {
                val nextRuns = KCron.parseAndBuild(cron).nextRunList(5000)
                        .filter { it.toJavaLocalDateTime().isBefore(java.time.LocalDateTime.now().plusYears(1)) }
                        .map { (it.toInstant(TimeZone.currentSystemDefault()) - Clock.System.now()).inWholeMinutes }
                nextRuns.forEach {
                    executor.schedule({ runBlocking { animationLoader.loadCron(name) } }, it, TimeUnit.MINUTES)
                }
            } catch (e: Exception) {
                logger.error("Error scheduling $name with cron '$cron'")
            }
        }
    }

    private fun nextRunInMinutes(cron: String) =
            (KCron.parseAndBuild(cron).nextRun!!.toInstant(TimeZone.currentSystemDefault()) - Clock.System.now()).inWholeMinutes

    private fun shutdownInternal() {
        runBlocking {
            shutdown()
        }
    }

    suspend fun wakePower() {
        logger.info("Waking up!")
        TreeState.state = TreeState.State.STARTING_UP
        tasmotaClient.switchOn()
        animationLoader.loadSunrise()
    }

    fun forceOn() {
        if (TreeState.state != TreeState.State.ON) {
            logger.info("Current state: ${TreeState.state}, forcing on")
            TreeState.state = TreeState.State.ON
        }
    }

    fun shuttingDown() {
        logger.info("Shutting down!")
        runBlocking {
            animationLoader.loadSunset()
        }
        TreeState.state = TreeState.State.SHUTTING_DOWN
    }

    suspend fun shutdown() {
        val now = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
        if (now.month == Month.DECEMBER && now.dayOfMonth == 31) {
            logger.info("No shutdown, fireworks!")
            return
        }
        logger.info("Shutdown. ")
        TreeState.state = TreeState.State.OFF
        tasmotaClient.switchOff()
    }

    fun fireworks() {
        TreeState.state = TreeState.State.FIREWORK
        logger.info("Fireworks!")
    }
}