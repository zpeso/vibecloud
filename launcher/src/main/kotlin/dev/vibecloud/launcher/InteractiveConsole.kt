package dev.vibecloud.launcher

import dev.vibecloud.api.cloud.Cloud
import dev.vibecloud.api.server.ServerType
import org.jline.reader.*
import org.jline.terminal.Terminal
import org.jline.terminal.TerminalBuilder
import org.jline.utils.AttributedString
import org.jline.utils.AttributedStringBuilder
import org.jline.utils.AttributedStyle

/**
 * JLine-powered interactive console: persistent history, context-aware tab completion, live syntax
 * highlighting while typing, background log printing above the input line, and Ctrl+C that stays
 * in the loop instead of killing the cloud.
 */
class InteractiveConsole(private val cloud: Cloud) : AutoCloseable {
    private val terminal: Terminal = buildTerminal()

    /**
     * Terminal construction with graceful degradation. The first attempt is the full interactive
     * terminal; if the environment is hostile (screen with an exotic/absent TERM can make JLine's
     * dumb-terminal capability loading throw an NPE), retries pin a type whose capabilities ship
     * inside the JLine jar, and finally fall back to a plain stdin/stdout terminal. The cloud must
     * never crash because the console looks wrong.
     */
    private fun buildTerminal(): Terminal {
        val attempts = listOf(
            "full interactive terminal" to {
                TerminalBuilder.builder().system(true).dumb(true).build()
            },
            "terminal with pinned type" to {
                TerminalBuilder.builder().system(true).dumb(true).type("dumb").build()
            },
            "plain stdin/stdout terminal" to {
                TerminalBuilder.builder().dumb(true).type("dumb")
                    .streams(System.`in`, System.out).build()
            },
        )
        attempts.forEach { (description, build) ->
            try {
                return build()
            } catch (failure: Throwable) {
                System.err.println("[console] $description unavailable: ${failure.message}")
            }
        }
        throw IllegalStateException("No usable terminal could be created")
    }

    private var cachedReader: LineReader? = null

    /** Prints a line above the active input and redraws the prompt; safe from any thread. */
    fun printAbove(line: String) {
        reader().printAbove(line)
    }

    /** Reads one command line at the main prompt. Returns null on EOF (Ctrl+D). */
    fun readLine(): String? = readLine(Cli.prompt())

    /** Reads one command line while attached to a service console. */
    fun readLineScreen(serviceName: String): String? = readLine(Cli.screenPrompt(serviceName))

    private fun readLine(prompt: String): String? = try {
        reader().readLine(prompt)
    } catch (_: UserInterruptException) {
        printAbove(Cli.dim("(ctrl+c) — type ") + Cli.command("exit") + Cli.dim(" to shut down"))
        ""
    } catch (_: EndOfFileException) {
        null
    }

    private fun reader(): LineReader {
        cachedReader?.let { return it }
        val built = LineReaderBuilder.builder()
            .terminal(terminal)
            .completer(SmartCompleter(cloud))
            .highlighter(BufferHighlighter(cloud))
            .variable(LineReader.HISTORY_SIZE, 500)
            .option(LineReader.Option.HISTORY_BEEP, false)
            .option(LineReader.Option.AUTO_LIST, true)
            .option(LineReader.Option.AUTO_MENU, true)
            .option(LineReader.Option.CASE_INSENSITIVE_SEARCH, true)
            .build()
        cachedReader = built
        return built
    }

    fun clear() {
        terminal.writer().print("\u001B[2J\u001B[H")
        terminal.writer().flush()
    }    fun banner() {
        printAbove(
            Cli.dim(" VibeCloud — type ") + Cli.command("help") +
                Cli.dim(" for commands, ") + Cli.highlight("Tab") + Cli.dim(" completes."),
        )
        printAbove("")
    }

    override fun close() {
        cachedReader = null
        runCatching { terminal.close() }
    }
}

/** Context-aware completion: commands, subcommands, then live group/service names, then flags. */
internal class SmartCompleter(private val cloud: Cloud) : Completer {
    override fun complete(reader: LineReader, line: ParsedLine, candidates: MutableList<Candidate>) {
        val words = line.words().filter { it.isNotBlank() }
        val soFar = words.dropLast(1)
        val current = line.word().orEmpty()

        when {
            soFar.isEmpty() -> CommandCatalog.commands.forEach { spec ->
                candidates += Candidate(spec.name, spec.name, null, spec.description, null, null, true)
            }

            soFar[0] == "group" && soFar.size == 1 -> addSubCommands("group", candidates)
            soFar[0] == "service" && soFar.size == 1 -> addSubCommands("service", candidates)
            soFar[0] == "cloud" && soFar.size == 1 -> addSubCommands("cloud", candidates)
            soFar[0] == "service" && soFar.size == 2 && soFar[1] == "create" ->
                cloud.groups.all().forEach { group ->
                    candidates += Candidate(group.name, group.name, "group", "group", null, null, true)
                }

            soFar[0] == "service" && soFar.size == 2 && soFar[1] in TARGET_SUBCOMMANDS ->
                cloud.services.all().forEach { service ->
                    candidates += Candidate(
                        service.name,
                        service.name,
                        service.groupName,
                        service.state.name.lowercase() + " · port " + service.port,
                        null,
                        null,
                        true,
                    )
                }

            soFar[0] == "group" && soFar.size == 2 && soFar[1] in listOf("info", "delete") ->
                cloud.groups.all().forEach { group ->
                    candidates += Candidate(group.name, group.name, "group", "group", null, null, true)
                }

            soFar[0] == "group" && soFar[1] == "create" && current.startsWith("-") ->
                CommandCatalog.flags.forEach { (flag, description) ->
                    candidates += Candidate(flag, flag, "flag", description, null, null, true)
                }

            soFar.contains("--type") && soFar[0] == "group" && soFar[1] == "create" ->
                ServerType.entries.forEach { type ->
                    candidates += Candidate(type.name, type.name, "server-type", null, null, null, true)
                }
        }
    }

    private fun addSubCommands(commandName: String, candidates: MutableList<Candidate>) {
        CommandCatalog.find(commandName)?.subcommands?.forEach { sub ->
            candidates += Candidate(sub.name, sub.name, null, sub.description, null, null, true)
        }
    }

    private companion object {
        val TARGET_SUBCOMMANDS = setOf("screen", "start", "stop", "restart", "info", "delete")
    }
}

/** Colors the typed input live: commands violet, groups pink, services cyan, flags dim, numbers amber. */
internal class BufferHighlighter(private val cloud: Cloud) : org.jline.reader.Highlighter {
    override fun highlight(reader: LineReader, buffer: String): AttributedString {
        val builder = AttributedStringBuilder()
        buffer.split(" ").forEachIndexed { index, token ->
            if (index > 0) builder.append(" ")
            val style = when {
                index == 0 && CommandCatalog.isCommand(token) -> AttributedStyle.DEFAULT.foreground(0xA78BFA)
                index == 0 -> AttributedStyle.DEFAULT.foreground(0xF87171)
                token.startsWith("--") -> AttributedStyle.DEFAULT.foreground(0x64748B)
                token in groupNames -> AttributedStyle.DEFAULT.foreground(0xF472B6)
                token in serviceNames -> AttributedStyle.DEFAULT.foreground(0x22D3EE)
                token.toLongOrNull() != null -> AttributedStyle.DEFAULT.foreground(0xFBBF24)
                else -> AttributedStyle.DEFAULT
            }
            builder.append(token, style)
        }
        return builder.toAttributedString()
    }

    private val groupNames: Set<String>
        get() = runCatching { cloud.groups.all().map { it.name }.toSet() }.getOrDefault(
            emptySet()
        )
    private val serviceNames: Set<String>
        get() = runCatching {
            cloud.services.all().map { it.name }.toSet()
        }.getOrDefault(emptySet())

    override fun setErrorPattern(pattern: java.util.regex.Pattern?) = Unit
    override fun setErrorIndex(errorIndex: Int) = Unit
}
