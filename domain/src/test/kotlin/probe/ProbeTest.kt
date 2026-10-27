package probe
import com.zyke.message.domain.classification.MessageClassifier
import com.zyke.message.domain.model.SmsMessage
import org.junit.Test
class ProbeTest {
    @Test fun probe() {
        val c = MessageClassifier()
        val raw = java.io.File(System.getProperty("msgs")).readText()
        val msgs = Regex("\"((?:[^\"\\\\]|\\\\.)*)\"").findAll(raw).map { it.groupValues[1].replace("\\n","\n").replace("\\\"","\"").replace("\\/","/").replace("\\\\","\\") }.toList()
        val out = StringBuilder()
        msgs.forEachIndexed { i, m ->
            val r = c.classify(SmsMessage(1, "AD-HDFCBK", m, 0L, true)).content
            out.append("${i+1}: ${r.toString().take(150)}\n")
        }
        java.io.File(System.getProperty("out")).writeText(out.toString())
    }
}
