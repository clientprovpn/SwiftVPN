package ir.swiftvpn.xray

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import android.util.Log
import ir.swiftvpn.engine.XrayIpc
import ir.swiftvpn.engine.XrayStore
import ir.swiftvpn.engine.XrayTester
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The measurement endpoint living in the :xray process.
 *
 * Latency/probe/speed tests each start a throwaway xray-core, i.e. they load
 * and drive libgojni. After the process split (see [XrayIpc]) that work must
 * happen HERE, next to the tunnel service — the UI process keeps libwg-go and
 * may never load a second Go runtime. The UI side (XrayRemote) binds and sends
 * REQ_* messages; we run the existing in-process XrayTester code and reply.
 */
class XrayTestService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var thread: HandlerThread
    private lateinit var messenger: Messenger

    override fun onCreate() {
        super.onCreate()
        thread = HandlerThread("xray-test").apply { start() }
        messenger = Messenger(Handler(thread.looper) { msg ->
            handle(msg)
            true
        })
    }

    override fun onBind(intent: Intent): IBinder = messenger.binder

    override fun onDestroy() {
        runCatching { thread.quitSafely() }
        super.onDestroy()
    }

    private fun handle(msg: Message) {
        val uuid = msg.data.getString(XrayIpc.K_UUID) ?: return
        val replyTo = msg.replyTo ?: return
        when (msg.what) {
            XrayIpc.REQ_LATENCY -> scope.launch {
                val store = XrayStore(this@XrayTestService)
                val profile = store.profiles().firstOrNull { it.uuid == uuid }
                val ms = if (profile != null) {
                    XrayTester.latency(this@XrayTestService, profile, store)
                } else {
                    null
                }
                replyTo.sendReply(
                    XrayIpc.RES_LATENCY,
                    Bundle().apply { putLong(XrayIpc.K_MS, ms ?: -1) },
                )
            }

            XrayIpc.REQ_PROBE -> scope.launch {
                val result = XrayTester.probe(
                    this@XrayTestService, uuid, XrayStore(this@XrayTestService),
                )
                replyTo.sendReply(
                    XrayIpc.RES_PROBE,
                    Bundle().apply {
                        putLong(XrayIpc.K_MS, result.latencyMs ?: -1)
                        putString(XrayIpc.K_COUNTRY, result.countryCode)
                        putString(XrayIpc.K_IP, result.egressIp)
                        putString(XrayIpc.K_ERROR, result.error)
                    },
                )
            }

            XrayIpc.REQ_SPEED -> scope.launch {
                val bps = XrayTester.downloadSpeed(
                    this@XrayTestService, uuid, XrayStore(this@XrayTestService),
                )
                replyTo.sendReply(
                    XrayIpc.RES_SPEED,
                    Bundle().apply { putLong(XrayIpc.K_BYTES_PER_SEC, bps ?: -1) },
                )
            }
        }
    }

    private fun Messenger.sendReply(what: Int, data: Bundle) {
        runCatching {
            send(Message.obtain(null, what).apply { this.data = data })
        }.onFailure { Log.w(TAG, "reply failed", it) }
    }

    companion object {
        private const val TAG = "XrayTestService"
    }
}
