@file:Suppress("DuplicatedCode", "MemberVisibilityCanBePrivate", "unused")

package coreLibrary.lib

import cf.wayzer.scriptAgent.define.Script
import cf.wayzer.scriptAgent.events.ScriptDisableEvent
import cf.wayzer.scriptAgent.listenTo
import cf.wayzer.scriptAgent.thisContextScript
import cf.wayzer.scriptAgent.util.DSLBuilder
import coreLibrary.lib.util.ServiceRegistry
import coreLibrary.lib.util.menu
import java.util.logging.Logger


class CommandContext : DSLBuilder(), Cloneable {
    // Should init if not empty
    var prefix: String = ""

    // Should init if not empty
    var arg = emptyList<String>()

    /** use for arg like '-v' */
    fun checkArg(p: String): Boolean {
        if (p !in arg) return false
        arg = arg.filterNot { it == p }
        return true
    }

    /**
     * message callback
     * should support async, otherwise set to {} after use
     * should support call from other thread, switch thread when need
     */
    var reply: (msg: PlaceHoldString) -> Unit = {}

    // Should not null if do TabComplete
    var replyTabComplete: ((list: List<String>) -> Nothing)? = null

    // Should init in RootCommand
    var hasPermission: (node: String) -> Boolean = { false }

    fun getSub(): CommandContext {
        return (clone() as CommandContext).apply {
            if (arg.isEmpty()) return@apply
            prefix += arg[0] + " "
            arg = arg.subList(1, arg.size)
        }
    }

    //===util===
    /**Can't be call in coroutine or other context, use [reply] instead*/
    @CommandInfo.CommandBuilder
    fun returnReply(msg: PlaceHoldString): Nothing {
        reply(msg)
        CommandInfo.Return()
    }
}
typealias CommandHandler = CommandContext.() -> Unit

interface TabCompleter {
    fun onComplete(context: CommandContext)
    fun CommandContext.onComplete(index: Int, body: () -> List<String>) {
        if (arg.size == index + 1)
            replyTabComplete?.invoke(body())
    }
}

class CommandInfo(
    val script: Script?,
    val name: String,
    val description: String,
    init: CommandInfo.() -> Unit = {}
) : DSLBuilder(), (CommandContext) -> Unit, TabCompleter {
    var usage = ""
    var aliases = emptyList<String>()
    var permission = ""
    private var onComplete: CommandHandler = {}

    @CommandBuilder
    fun onComplete(body: CommandHandler) {
        this.onComplete = body
    }

    private var body: CommandHandler = {}

    @CommandBuilder
    fun body(body: CommandHandler) {
        this.body = body
    }

    init {
        this.init()
    }

    override fun onComplete(context: CommandContext) {
        onComplete.invoke(context)
        (body as? TabCompleter)?.onComplete(context)
    }

    override fun invoke(context: CommandContext) {
        try {
            if (permission.isNotBlank() && !context.hasPermission(permission))
                context.replyNoPermission()
            body.invoke(context)
        } catch (_: Return) {
        } catch (e: Exception) {
            context.reply("[red]执行命令出现异常: {msg}".with("msg" to (e.message ?: "")))
            e.printStackTrace()
        }
    }

    @CommandBuilder
    fun CommandContext.replyNoPermission(): Nothing {
        reply("[red]你没有执行该命令的权限".with())
        Return()
    }

    @CommandBuilder
    fun CommandContext.replyUsage(): Nothing {
        reply("[red]参数错误: {prefix} {usage}".with("prefix" to prefix, "usage" to (usage)))
        Return()
    }

    object Return : Throwable("Direct return command") {
        @CommandBuilder
        operator fun invoke(): Nothing {
            throw this
        }
    }

    @DslMarker
    annotation class CommandBuilder
}

open class Commands : (CommandContext) -> Unit, TabCompleter {
    protected val subCommands = mutableMapOf<String, CommandInfo>()

    /**
     * @return [subCommands] when [context] is null
     */
    open fun getSubCommands(context: CommandContext?): Map<String, CommandInfo> = subCommands
    fun getSub(context: CommandContext): CommandInfo? {
        return context.arg.getOrNull(0)?.let { getSubCommands(context)[it.lowercase()] }
    }

    override fun onComplete(context: CommandContext) {
        context.onComplete(0) { getSubCommands(context).keys.toList() }
        getSub(context)?.onComplete(context.getSub())
    }

    override operator fun invoke(context: CommandContext) {
        getSub(context)?.invoke(context.getSub()) ?: onHelp(context, false)
    }

    open fun onHelp(context: CommandContext, explicit: Boolean) {
        val showDetail = context.checkArg("-v")
        if (showDetail && !context.hasPermission("command.detail"))
            return context.reply("[red]必须拥有command.detail权限才能查看完整help".with())
        val page = context.arg.firstOrNull()?.toIntOrNull() ?: 1
        context.reply(menu(context.prefix, getSubCommands(context).values.toSet().filter {
            showDetail || it.permission.isBlank() || context.hasPermission(it.permission)
        }, page, 10) {
            context.helpInfo(it, showDetail)
        })
    }

    protected open fun addSub(name: String, command: CommandInfo, isAliases: Boolean) {
        val existed = subCommands[name.lowercase()]?.takeIf { it.script?.enabled == true } ?: let {
            subCommands[name.lowercase()] = command
            return
        }
        if (existed == command) return
        if (isAliases) {
            Logger.getLogger("[CommandApi]").warning("duplicate aliases $name($command) with $existed")
        } else {
            Logger.getLogger("[CommandApi]").warning("replace command $name: NOW:$command OLD:$existed")
            subCommands[name.lowercase()] = command //name is more important
        }
    }

    open fun removeSub(name: String) {
        subCommands.remove(name.lowercase())
    }

    fun addSub(command: CommandInfo) {
        addSub(command.name, command, false)
        command.aliases.forEach {
            addSub(it, command, true)
        }
    }

    fun removeSub(command: CommandInfo) {
        subCommands.remove(command.name.lowercase(), command)
        command.aliases.forEach {
            subCommands.remove(it.lowercase(), command)
        }
    }

    open fun removeAll(script: Script) {
        val toRemove = mutableListOf<String>()
        subCommands.forEach { (k, s) ->
            if (s.script == script) toRemove.add(k)
        }
        toRemove.forEach(::removeSub)
    }

    operator fun plusAssign(command: CommandInfo) = addSub(command)
    fun autoRemove(script: Script) {
        script.onDisable {
            removeAll(script)
        }
    }

    init {
        addSub(CommandInfo(null, "help", "帮助指令") {
            usage = "[-v] [page]"
            aliases = listOf("帮助")
            body {
                prefix = prefix.removeSuffix("help ").removeSuffix("帮助 ")
                onHelp(this, true)
            }
        })
    }

    companion object {
        val rootProvider = ServiceRegistry<Commands>()
        val controlCommand = Commands()

        init {
            thisContextScript().apply {
                rootProvider.subscribe(this) {
                    it += CommandInfo(null, "ScriptAgent", "ScriptAgent 控制指令") {
                        aliases = listOf("sa")
                        permission = "scriptAgent.admin"
                        body(controlCommand)
                    }
                }
                listenTo<ScriptDisableEvent> {
                    rootProvider.getOrNull()?.removeAll(script)
                }
            }
        }

        fun CommandContext.helpInfo(it: CommandInfo, showDetail: Boolean): PlaceHoldString {
            val alias = if (it.aliases.isEmpty()) "" else it.aliases.joinToString(prefix = "(", postfix = ")")
            val detail = buildString {
                if (!showDetail) return@buildString
                @Suppress("UNNECESSARY_SAFE_CALL")//Runtime compile fail
                if (it.script != null) append(" | ${it.script?.id}")
                if (it.permission.isNotBlank()) append(" | ${it.permission}")
            }
            return "[light_yellow]{prefix}{name}[light_red]{aliases} [white]{usage}  [light_cyan]{desc}[cyan]{detail}".with(
                "prefix" to prefix, "name" to it.name, "aliases" to alias,
                "usage" to it.usage, "desc" to it.description, "detail" to detail
            )
        }
    }
}