package com.phairplay.dlna.scpd

/**
 * ScpdBuilder — a tiny DSL that emits a UPnP Service Control Protocol Description (SCPD) document.
 *
 * WHY: The three SCPDs are ~150 lines of repetitive XML each; generating them from a compact list of
 * actions and state variables keeps every file under the 400-line rule and makes the XML impossible to
 * mistype (argument ↔ state-variable links are written once).
 *
 * HOW: `scpd { action("Play") { input("InstanceID", "A_ARG_TYPE_InstanceID") }; variable("LastChange", evented = true) }`
 *
 * Action/argument/variable names passed in here are trusted code constants written by us (the three
 * `*Scpd` objects), never network input, so [build] does not escape them.
 */
class ScpdBuilder {

    internal class Argument(val name: String, val direction: String, val variable: String)
    private class Action(val name: String, val arguments: List<Argument>)
    private class Variable(
        val name: String, val dataType: String, val evented: Boolean,
        val allowed: List<String>, val range: IntRange?, val default: String?
    )

    /** Receiver for an [action] block: declares that action's `in`/`out` arguments in call order. */
    class ActionScope {
        internal val arguments = mutableListOf<Argument>()
        fun input(name: String, variable: String) { arguments += Argument(name, "in", variable) }
        fun output(name: String, variable: String) { arguments += Argument(name, "out", variable) }
    }

    private val actions = mutableListOf<Action>()
    private val variables = mutableListOf<Variable>()

    fun action(name: String, block: ActionScope.() -> Unit = {}) {
        actions += Action(name, ActionScope().apply(block).arguments)
    }

    fun variable(
        name: String,
        dataType: String = "string",
        evented: Boolean = false,
        allowed: List<String> = emptyList(),
        range: IntRange? = null,
        default: String? = null
    ) {
        variables += Variable(name, dataType, evented, allowed, range, default)
    }

    fun build(): String {
        val actionXml = actions.joinToString("") { action ->
            val args = action.arguments.joinToString("") { arg ->
                "<argument><name>${arg.name}</name><direction>${arg.direction}</direction>" +
                    "<relatedStateVariable>${arg.variable}</relatedStateVariable></argument>"
            }
            val list = if (args.isEmpty()) "" else "<argumentList>$args</argumentList>"
            "<action><name>${action.name}</name>$list</action>"
        }
        val variableXml = variables.joinToString("") { variable ->
            val allowed = if (variable.allowed.isEmpty()) "" else
                "<allowedValueList>" + variable.allowed.joinToString("") { "<allowedValue>$it</allowedValue>" } + "</allowedValueList>"
            val range = variable.range?.let {
                "<allowedValueRange><minimum>${it.first}</minimum><maximum>${it.last}</maximum><step>1</step></allowedValueRange>"
            } ?: ""
            val default = variable.default?.let { "<defaultValue>$it</defaultValue>" } ?: ""
            "<stateVariable sendEvents=\"${if (variable.evented) "yes" else "no"}\"><name>${variable.name}</name>" +
                "<dataType>${variable.dataType}</dataType>$default$allowed$range</stateVariable>"
        }
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
            "<scpd xmlns=\"urn:schemas-upnp-org:service-1-0\">" +
            "<specVersion><major>1</major><minor>0</minor></specVersion>" +
            "<actionList>$actionXml</actionList>" +
            "<serviceStateTable>$variableXml</serviceStateTable>" +
            "</scpd>"
    }
}

fun scpd(block: ScpdBuilder.() -> Unit): String = ScpdBuilder().apply(block).build()
