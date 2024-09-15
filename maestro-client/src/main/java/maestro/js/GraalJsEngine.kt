package maestro.js

import okhttp3.OkHttpClient
import okhttp3.Protocol
import org.graalvm.polyglot.Context
import org.graalvm.polyglot.Source
import org.graalvm.polyglot.Value
import org.graalvm.polyglot.proxy.ProxyObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import java.util.logging.Handler
import java.util.logging.LogRecord
import maestro.utils.Env
import maestro.utils.StringUtils.evalTruthness
import org.graalvm.polyglot.HostAccess

private val NULL_HANDLER = object : Handler() {
    override fun publish(record: LogRecord?) {}

    override fun flush() {}

    override fun close() {}
}

class GraalJsEngine(
    httpClient: OkHttpClient = OkHttpClient.Builder()
        .readTimeout(5, TimeUnit.MINUTES)
        .writeTimeout(5, TimeUnit.MINUTES)
        .protocols(listOf(Protocol.HTTP_1_1))
        .build(),
    platform: String = "unknown",
) : JsEngine {

    private val openContexts = HashSet<Context>()

    private val httpBinding = GraalJsHttp(httpClient)
    private val outputBinding = HashMap<String, Any>()
    private val maestroBinding = HashMap<String, Any?>()
    private val envBinding = HashMap<String, String>()

    private var onLogMessage: (String) -> Unit = {}

    private var platform = platform

    override fun close() {
        openContexts.forEach { it.close() }
    }

    override fun onLogMessage(callback: (String) -> Unit) {
        onLogMessage = callback
    }

    override fun enterScope() {}

    override fun leaveScope() {}

    override fun putEnv(key: String, value: String) {
        this.envBinding[key] = value
    }

    override fun setCopiedText(text: String?) {
        this.maestroBinding["copiedText"] = text
    }

    override fun evaluateScript(
        script: String,
        env: Map<String, String>,
        sourceName: String,
        runInSubScope: Boolean,
    ): Value {
        envBinding.putAll(env)
        val source = Source.newBuilder("js", script, sourceName).build()
        return createContext().eval(source)
    }

    private fun Context.Builder.setAccessConfigFromEnv(): Context.Builder {
        val allowHostAccess = Env.getSystemEnv(HOST_ACCESS_ENV).evalTruthness()
        val allowClassLookup = Env.getSystemEnv(CLASS_LOOKUP_ENV).evalTruthness()

        return this
            .allowHostClassLookup { allowClassLookup }
            .allowHostAccess(if (allowHostAccess) HostAccess.ALL else HostAccess.EXPLICIT)
    }

    private fun createContext(): Context {
        val outputStream = object : ByteArrayOutputStream() {
            override fun flush() {
                super.flush()
                val log = toByteArray().decodeToString().removeSuffix("\n")
                onLogMessage(log)
                reset()
            }
        }

        val context = Context.newBuilder("js")
            .option("js.strict", "true")
            .setAccessConfigFromEnv()
            .logHandler(NULL_HANDLER)
            .out(outputStream)
            .build()

        openContexts.add(context)

        envBinding.forEach { (key, value) -> context.getBindings("js").putMember(key, value) }

        context.getBindings("js").putMember("http", httpBinding)
        context.getBindings("js").putMember("output", ProxyObject.fromMap(outputBinding))
        context.getBindings("js").putMember("maestro", ProxyObject.fromMap(maestroBinding))

        maestroBinding["platform"] = platform

        context.eval(
            "js", """
            // Prevent a reference error on referencing undeclared variables. Enables patterns like {MY_ENV_VAR || 'default-value'}.
            // Instead of throwing an error, undeclared variables will evaluate to undefined.
            Object.setPrototypeOf(globalThis, new Proxy(Object.prototype, {
                has(target, key) {
                    return true;
                }
            }))
            function json(text) {
                return JSON.parse(text)
            }
            function relativePoint(x, y) {
                var xPercent = Math.ceil(x * 100) + '%'
                var yPercent = Math.ceil(y * 100) + '%'
                return xPercent + ',' + yPercent
            }
        """.trimIndent()
        )

        return context
    }

    companion object {
        const val HOST_ACCESS_ENV = "MAESTRO_CLI_DANGEROUS_GRAALJS_ALLOW_HOST_ACCESS"
        const val CLASS_LOOKUP_ENV = "MAESTRO_CLI_DANGEROUS_GRAALJS_ALLOW_CLASS_LOOKUP"
    }
}
