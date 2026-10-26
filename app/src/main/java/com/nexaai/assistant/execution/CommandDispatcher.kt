package com.nexaai.assistant.execution

import com.nexaai.assistant.data.*
import com.nexaai.assistant.domain.*
import java.math.BigDecimal
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

interface CommandController {
    suspend fun check(command: AssistantCommand): CommandResult?
    suspend fun execute(command: AssistantCommand): CommandResult
}
interface VolumeController : CommandController
interface AppLauncher : CommandController
interface MediaController : CommandController
interface ListeningController : CommandController
interface RadioController : CommandController { val band: FmBand? }

data class FmBand(val minimumMhz: BigDecimal, val maximumMhz: BigDecimal, val stepMhz: BigDecimal) {
    init { require(minimumMhz.signum() > 0 && maximumMhz >= minimumMhz && stepMhz.signum() > 0) }
    fun contains(frequency: BigDecimal) = frequency >= minimumMhz && frequency <= maximumMhz &&
        (frequency - minimumMhz).remainder(stepMhz).compareTo(BigDecimal.ZERO) == 0
}

class UnsupportedRadioController : RadioController {
    override val band: FmBand? = null
    override suspend fun check(command: AssistantCommand) = CommandResult.Unsupported("Radio unavailable: no verified K2501 tuner interface or band plan")
    override suspend fun execute(command: AssistantCommand) = check(command)
}

class UnavailableListeningController : ListeningController {
    override suspend fun check(command: AssistantCommand) = CommandResult.Unsupported("Listening unavailable: no offline speech engine/model is installed; microphone remains off")
    override suspend fun execute(command: AssistantCommand) = check(command)
}

data class ExecutionConfiguration(val snapshot: ConfigurationSnapshot, val apps: List<AllowedApp>)

class CommandValidator {
    fun validate(command: AssistantCommand, environment: ExecutionConfiguration, band: FmBand?): CommandResult? {
        if (!environment.snapshot.ready || environment.snapshot.warning != null)
            return CommandResult.Failed("Configuration unavailable; reset or repair settings before execution", "CONFIGURATION_UNAVAILABLE")
        val config = environment.snapshot.configuration
        try { ConfigurationValidator.validate(config) } catch (invalid: IllegalArgumentException) {
            return CommandResult.InvalidParameters("Invalid command configuration")
        }
        if (!config.settings.assistantEnabled) return CommandResult.InvalidParameters("Assistant is disabled")
        if (config.commands.single { it.id == command.id }.enabled.not()) return CommandResult.InvalidParameters("Command is disabled")
        return when (command) {
            is AssistantCommand.SetVolume -> if (command.percent !in 0..100) CommandResult.InvalidParameters("Volume must be from 0 to 100") else null
            is AssistantCommand.LaunchApp -> if (environment.apps.none { it.packageName == command.appId }) CommandResult.InvalidParameters("App is not allowlisted") else null
            is AssistantCommand.TuneFm -> when {
                command.frequencyMhz.signum() <= 0 -> CommandResult.InvalidParameters("FM frequency must be positive")
                band == null -> CommandResult.Unsupported("FM tuning unavailable: no verified tuner band and step")
                !band.contains(command.frequencyMhz) -> CommandResult.InvalidParameters("Frequency is outside the verified band or step")
                else -> null
            }
            is AssistantCommand.Action -> if (command.id in setOf(CommandId.SET_VOLUME, CommandId.LAUNCH_APP, CommandId.TUNE_FM))
                CommandResult.InvalidParameters("Required command parameter is missing") else null
        }
    }
}

class PreparedCommand internal constructor(val transcript: String, val command: AssistantCommand) {
    private val available = AtomicBoolean(true)
    fun cancel() = available.compareAndSet(true, false)
    internal fun claim() = available.compareAndSet(true, false)
}

sealed interface Preparation {
    data class Ready(val pending: PreparedCommand) : Preparation
    data class Rejected(val result: CommandResult) : Preparation
}

class CommandDispatcher(
    private val configuration: suspend () -> ExecutionConfiguration,
    private val volume: VolumeController,
    private val apps: AppLauncher,
    private val media: MediaController,
    private val radio: RadioController,
    private val listening: ListeningController = UnavailableListeningController(),
    private val parser: RuleBasedCommandParser = RuleBasedCommandParser(),
    private val validator: CommandValidator = CommandValidator(),
) {
    private val mutex = Mutex()
    private val prepared = AtomicReference<PreparedCommand?>(null)
    private val lastResponse = AtomicReference<String?>(null)

    suspend fun availability(id: CommandId): String {
        val environment = configuration()
        val command = if (id == CommandId.LAUNCH_APP) {
            val app = environment.apps.firstOrNull() ?: return "No apps allowlisted"
            AssistantCommand.LaunchApp(app.packageName)
        } else AssistantCommand.Action(id)
        return boundary { controller(id)?.check(command) }?.message
            ?: if (id in setOf(CommandId.HELP, CommandId.REPEAT_RESPONSE, CommandId.CANCEL)) "Local command; no hardware action"
            else "Android API available; target hardware not verified"
    }

    private fun discardPending() = prepared.getAndSet(null)?.cancel() == true

    private fun remember(result: CommandResult): CommandResult {
        lastResponse.set(result.message)
        return result
    }

    fun cancelPending(): CommandResult = remember(if (discardPending()) CommandResult.Cancelled
        else CommandResult.InvalidParameters("No pending interpretation to cancel"))

    suspend fun prepare(transcript: String): Preparation = mutex.withLock {
        val environment = configuration()
        when (val parsed = parser.parse(transcript, environment.snapshot.configuration, environment.apps)) {
            is ParseResult.Rejected -> { discardPending(); Preparation.Rejected(remember(parsed.result)) }
            is ParseResult.Resolved -> {
                val failure = validate(parsed.command, environment)
                if (failure != null) { discardPending(); Preparation.Rejected(remember(failure)) }
                else if (parsed.command.id == CommandId.CANCEL) Preparation.Rejected(cancelPending())
                else {
                    discardPending()
                    val pending = PreparedCommand(transcript, parsed.command)
                    prepared.set(pending)
                    Preparation.Ready(pending)
                }
            }
        }
    }

    suspend fun execute(pending: PreparedCommand): CommandResult = mutex.withLock {
        if (!pending.claim()) return@withLock remember(CommandResult.Cancelled)
        prepared.compareAndSet(pending, null)
        val environment = configuration()
        val reparsed = parser.parse(pending.transcript, environment.snapshot.configuration, environment.apps)
        if (reparsed != ParseResult.Resolved(pending.command))
            return@withLock remember(CommandResult.InvalidParameters("Configuration changed; interpret the command again"))
        executeValidated(pending.command, environment)
    }

    suspend fun dispatch(command: AssistantCommand): CommandResult = mutex.withLock {
        executeValidated(command, configuration())
    }

    private suspend fun validate(command: AssistantCommand, environment: ExecutionConfiguration): CommandResult? =
        validator.validate(command, environment, radio.band) ?: boundary { controller(command.id)?.check(command) }

    private suspend fun executeValidated(command: AssistantCommand, environment: ExecutionConfiguration): CommandResult {
        val failure = validate(command, environment)
        if (failure != null) return remember(failure)
        currentCoroutineContext().ensureActive()
        val result = boundary {
            when (command.id) {
                CommandId.HELP -> CommandResult.Success(environment.snapshot.configuration.commands.filter { it.enabled }
                    .map { definition ->
                        "${definition.aliases.joinToString("; ")} - ${availability(definition.id)}"
                    }.joinToString("\n"))
                CommandId.REPEAT_RESPONSE -> lastResponse.get()?.let { CommandResult.Success(it) }
                    ?: CommandResult.InvalidParameters("No previous response to repeat")
                CommandId.CANCEL -> cancelPending()
                else -> controller(command.id)!!.execute(command)
            }
        } ?: CommandResult.Failed("Controller returned no result", "EMPTY_RESULT")
        if (command.id != CommandId.REPEAT_RESPONSE) remember(result)
        return result
    }

    private fun controller(id: CommandId): CommandController? = when (id) {
        CommandId.SET_VOLUME, CommandId.VOLUME_UP, CommandId.VOLUME_DOWN, CommandId.MUTE, CommandId.UNMUTE -> volume
        CommandId.LAUNCH_APP, CommandId.GO_HOME -> apps
        CommandId.PLAY, CommandId.PAUSE, CommandId.NEXT_TRACK, CommandId.PREVIOUS_TRACK -> media
        CommandId.OPEN_RADIO, CommandId.TUNE_FM, CommandId.SEEK_NEXT, CommandId.SEEK_PREVIOUS,
        CommandId.MUTE_RADIO, CommandId.UNMUTE_RADIO, CommandId.AUTO_MEMORY_SCAN,
        CommandId.RADIO_INTRO_SCAN -> radio
        CommandId.START_LISTENING, CommandId.STOP_LISTENING -> listening
        else -> null
    }

    private suspend fun boundary(action: suspend () -> CommandResult?): CommandResult? = try {
        action()
    } catch (cancelled: CancellationException) { throw cancelled
    } catch (denied: SecurityException) {
        CommandResult.PermissionRequired("device_control", "Android denied device control")
    } catch (failure: IllegalStateException) {
        CommandResult.Failed("Device controller failed", "CONTROLLER_STATE")
    } catch (failure: IllegalArgumentException) {
        CommandResult.Failed("Device controller rejected the request", "CONTROLLER_ARGUMENT")
    }
}