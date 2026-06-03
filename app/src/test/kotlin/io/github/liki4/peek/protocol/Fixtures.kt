package io.github.liki4.peek.protocol

/**
 * Wire-bytes fixtures captured against a live CC_23 (SN 10200901) during the
 * 2026-05-30 validation session. Source: `test_logs/keep_test_20260530_152901.jsonl`
 * (held in `/Users/switch/Temp/Keep/test_logs/`).
 *
 * These exact byte sequences were sent / received from the bike. Any change to
 * the protocol layer that breaks these tests breaks compatibility with real
 * firmware.
 */
object Fixtures {

    fun hex(s: String): ByteArray {
        val clean = s.replace(" ", "").replace("\n", "")
        require(clean.length % 2 == 0) { "odd hex length: ${clean.length}" }
        return ByteArray(clean.length / 2) {
            ((Character.digit(clean[it * 2], 16) shl 4) or Character.digit(clean[it * 2 + 1], 16)).toByte()
        }
    }

    // ---------- Whole-packet fixtures ----------

    /** TX  GET  1/1 identity. method=GET, opcode=IDENTITY, sub_seq=6, seq=0x367E. */
    val IDENTITY_TX = hex(
        "a5a5a00623003216ef235501367e0600000001b3312f31ff" +
        "64656164626565663030616263646566007b2e"
    )

    /** TX  PUT  106/3 auth. UserInfo{deadbeef..., deadbeefcafe1234, 70.0kg, ts=1780126141}. */
    val AUTH_TX = hex(
        "a5a5a0074b003216ef235503367f0700000002b53130362f33ff" +
        "0a186465616462656566303030303030303030303030303030301210" +
        "646561646265656663616665313233341d00008c4220bda3ead006" +
        "5d62"
    )

    /** TX  OBSERVE  106/4. method=GET, opcode=0x61, sub_seq=0x65 (magic), b18=0x04. */
    val OBSERVE_106_4_TX = hex(
        "a5a5a00815003216ef235501368065000000046100553130362f344535"
    )

    /** RX  RSP  106/1 DeviceInfo. CC_23 / firmware 1.0.12 / lifetime 312843s / 1262471m. */
    val DEVICE_INFO_RX = hex(
        "a5a5a00a4800ef233216554536820a00000001b53130362f31ff" +
        "0a0543435f32331210433353315857343031303230303930311885" +
        "022203312e302a06312e302e3132388b8c134087874d488502" +
        "750c"
    )

    /** RX  RSP  106/7 TrainData mid-ride with rpm=14, watt=4, field8=1. */
    val TRAIN_DATA_RX_RPM14 = hex(
        "a5a5a0121a00ef2332165545368a1200000001b53130362f37ff" +
        "300e38044001" +
        "b9b4"
    )

    /** RX  PUSH 106/4 TrainingStatus: TRAINING(2), changedByDevice=1 (user pressed knob). */
    val STATUS_PUSH_TRAINING = hex(
        "a5a5a0141800ef233216554529476500000004b53130362f34ff" +
        "08021001" +
        "3640"
    )

    /** RX  RSP  106/7 TrainData early in TRAINING: startTime=1780126150, resistance=1, status=1. */
    val TRAIN_DATA_RX_FULL = hex(
        "a5a5a0152000ef2332165545368c1400000001b53130362f37ff" +
        "08c6a3ead006280140025801" +
        "f453"
    )

    // ---------- Payload-only fixtures (post-CRC strip + route strip) ----------

    /** DeviceInfo payload (everything after the route + 0xFF separator). */
    val DEVICE_INFO_PAYLOAD = hex(
        "0a0543435f32331210433353315857343031303230303930311885" +
        "022203312e302a06312e302e3132388b8c134087874d488502"
    )

    /** TrainData (full fields) payload. */
    val TRAIN_DATA_FULL_PAYLOAD = hex("08c6a3ead006280140025801")

    /** TrainingStatus push payload. */
    val STATUS_PUSH_PAYLOAD = hex("08021001")
}
