package com.nico.obd2dash.profiles

import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

/** Familles décrites par le moteur de rejeu ; cela n'active aucun transport. */
enum class ProfileProtocol { KWP_FAST, KWP_SLOW, CAN_11, CAN_29 }

/** Adresses du calculateur vues depuis le testeur : émission vers lui, réception de lui. */
data class EcuEndpoint(val protocol: ProfileProtocol, val requestAddress: String, val responseAddress: String)

/** Les champs sont opaques et exacts : aucune conversion implicite décimal/hex ou VIN/modèle. */
data class EcuFingerprint(val endpoint: EcuEndpoint, val scheme: String, val fields: Map<String, String>)

data class ProfileProvenance(
    val source: String,
    val revision: String,
    val sha256: String,
    val license: String,
    val attribution: String
)

enum class ProfileEvidence { IDENTIFICATION_ONLY, REPLAY_VERIFIED }
enum class ProfileSession { DEFAULT, EXTENDED, UNKNOWN }
enum class ByteOrder { BIG_ENDIAN, LITTLE_ENDIAN }

/** Liste fermée de lectures. Aucun script AT, changement de session ni service d'écriture. */
enum class IdentifierRead(val service: Int, val identifierBytes: Int) {
    KWP_LOCAL_IDENTIFIER(0x21, 1), UDS_DATA_IDENTIFIER(0x22, 2)
}

data class LinearSignal(
    val id: String,
    val unit: String,
    val offset: Int,
    val byteCount: Int,
    val byteOrder: ByteOrder = ByteOrder.BIG_ENDIAN,
    val signed: Boolean = false,
    val multiplier: Double = 1.0,
    val additive: Double = 0.0,
    /** Sentinelles exprimées dans la représentation brute non signée. */
    val unavailableRawValues: Set<Long> = emptySet()
) {
    internal fun validFor(dataLength: Int): Boolean =
        id.matches(Regex("[a-z][a-z0-9_]{0,63}")) && unit.isNotBlank() &&
            offset >= 0 && byteCount in 1..4 && offset <= dataLength - byteCount &&
            multiplier.isFinite() && additive.isFinite() &&
            unavailableRawValues.all { it in 0 until (1L shl (byteCount * 8)) }

    internal fun decode(data: List<Int>): Double? {
        var raw = 0L
        val indices = if (byteOrder == ByteOrder.BIG_ENDIAN) {
            offset until offset + byteCount
        } else {
            (offset until offset + byteCount).reversed()
        }
        for (index in indices) raw = (raw shl 8) or data[index].toLong()
        if (raw in unavailableRawValues) return null
        val bits = byteCount * 8
        val value = if (signed && (raw and (1L shl (bits - 1))) != 0L) raw - (1L shl bits) else raw
        return (value * multiplier + additive).takeIf { it.isFinite() }
    }
}

data class ReadExample(val responsePayloadHex: String, val expectedValues: Map<String, Double?>)

data class ProfileRead(
    val id: String,
    val service: IdentifierRead,
    val identifier: Int,
    val dataLength: Int,
    val signals: List<LinearSignal>,
    val intervalMs: Long,
    val timeoutMs: Long,
    val examples: List<ReadExample>
) {
    val requestHex: String
        get() = "%02X%s".format(Locale.ROOT, service.service,
            "%0${service.identifierBytes * 2}X".format(Locale.ROOT, identifier))
    private val positivePrefix: String
        get() = "%02X%s".format(Locale.ROOT, service.service + 0x40, requestHex.drop(2))

    internal fun decodePayload(hex: String): List<DecodedSignal> {
        val bytes = strictHexBytes(hex)
        val prefix = strictHexBytes(positivePrefix)
        require(bytes.take(prefix.size) == prefix) { "Réponse non corrélée à $requestHex" }
        require(bytes.size == prefix.size + dataLength) { "Longueur de réponse incorrecte" }
        val data = bytes.drop(prefix.size)
        return signals.map { DecodedSignal(it.id, it.unit, it.decode(data)) }
    }
}

data class ManufacturerProfile(
    val id: String,
    val version: String,
    val identity: EcuFingerprint,
    val provenance: ProfileProvenance,
    val evidence: ProfileEvidence,
    val session: ProfileSession,
    val defaultSessionEvidence: String,
    val reads: List<ProfileRead>
)

data class DecodedSignal(val id: String, val unit: String, val value: Double?)

data class RecordedReply(val endpoint: EcuEndpoint, val requestHex: String, val responsePayloadHex: String)

/** Une réponse enregistrée doit appartenir au même ECU et à la même requête. */
class ReplayRead internal constructor(val endpoint: EcuEndpoint, private val definition: ProfileRead) {
    val id: String get() = definition.id
    val requestHex: String get() = definition.requestHex
    val intervalMs: Long get() = definition.intervalMs
    val timeoutMs: Long get() = definition.timeoutMs

    fun decode(reply: RecordedReply): List<DecodedSignal> {
        require(reply.endpoint == endpoint) { "Réponse d'un autre calculateur ou protocole" }
        require(reply.requestHex == requestHex) { "Échange associé à une autre requête" }
        return definition.decodePayload(reply.responsePayloadHex)
    }
}

enum class ProfileMatchStatus { INCOMPLETE_IDENTITY, NO_MATCH, AMBIGUOUS, BLOCKED, READY_FOR_REPLAY }

/** Résultat exclusivement destiné au rejeu. Aucune méthode d'exécution sur une sonde. */
data class ProfileSelection(
    val status: ProfileMatchStatus,
    val profileId: String? = null,
    val profileVersion: String? = null,
    val reasons: List<String> = emptyList(),
    val reads: List<ReplayRead> = emptyList()
)

class ManufacturerProfiles(profiles: List<ManufacturerProfile>) {
    // Les DTO peuvent être construits avec des collections mutables par un importateur.
    // Ne jamais laisser une mutation externe modifier une sélection déjà vérifiée.
    private val profiles = profiles.map { profile ->
        profile.copy(
            identity = profile.identity.copy(fields = profile.identity.fields.toMap()),
            reads = profile.reads.map { read ->
                read.copy(
                    signals = read.signals.map { it.copy(unavailableRawValues = it.unavailableRawValues.toSet()) },
                    examples = read.examples.map { it.copy(expectedValues = it.expectedValues.toMap()) }
                )
            }
        )
    }
    fun select(identity: EcuFingerprint): ProfileSelection {
        if (!validIdentity(identity)) return ProfileSelection(ProfileMatchStatus.INCOMPLETE_IDENTITY)
        // Même protocole, mêmes adresses, même schéma ET même ensemble de champs.
        // Un profil voisin ou moins précis ne prend jamais la place de l'identité reçue.
        val matching = profiles.filter { it.identity == identity }
        if (matching.isEmpty()) return ProfileSelection(ProfileMatchStatus.NO_MATCH)
        if (matching.size != 1) return ProfileSelection(ProfileMatchStatus.AMBIGUOUS,
            reasons = matching.map { "${it.id}@${it.version}" })
        val profile = matching.single()
        val errors = validate(profile)
        if (errors.isNotEmpty()) return ProfileSelection(ProfileMatchStatus.BLOCKED,
            profile.id, profile.version, errors)
        return ProfileSelection(ProfileMatchStatus.READY_FOR_REPLAY, profile.id, profile.version,
            reads = profile.reads.map { ReplayRead(profile.identity.endpoint, it) })
    }

    private fun validate(profile: ManufacturerProfile): List<String> = buildList {
        if (profile.id.isBlank() || profile.version.isBlank()) add("Profil sans identifiant ou version")
        val source = profile.provenance
        if (!(source.source.startsWith("https://") || source.source.startsWith("local:")) ||
            source.revision.isBlank() || !source.sha256.matches(Regex("[0-9a-fA-F]{64}")) ||
            source.license.isBlank() || source.attribution.isBlank()) add("Provenance incomplète")
        if (profile.evidence != ProfileEvidence.REPLAY_VERIFIED) add("Profil limité à l'identification")
        if (profile.session != ProfileSession.DEFAULT || profile.defaultSessionEvidence.isBlank()) {
            add("Accès sans changement de session non documenté")
        }
        if (profile.reads.isEmpty()) add("Aucune définition de mesure")
        if (profile.reads.size > 256) add("Trop de lectures")
        if (profile.reads.map { it.id }.distinct().size != profile.reads.size ||
            profile.reads.map { it.service to it.identifier }.distinct().size != profile.reads.size) {
            add("Lectures dupliquées")
        }
        for (read in profile.reads.take(256)) {
            val validIdentifier = read.identifier in 0 until (1 shl (8 * read.service.identifierBytes))
            val validProtocol = when (read.service) {
                IdentifierRead.KWP_LOCAL_IDENTIFIER -> profile.identity.endpoint.protocol in
                    setOf(ProfileProtocol.KWP_FAST, ProfileProtocol.KWP_SLOW)
                IdentifierRead.UDS_DATA_IDENTIFIER -> profile.identity.endpoint.protocol in
                    setOf(ProfileProtocol.CAN_11, ProfileProtocol.CAN_29)
            }
            val maxDataLength = when (read.service) {
                IdentifierRead.KWP_LOCAL_IDENTIFIER -> 253 // Charge KWP <= 255, écho service/id de deux octets.
                IdentifierRead.UDS_DATA_IDENTIFIER -> 4092 // ISO-TP classique <= 4095, écho de trois octets.
            }
            val valid = read.id.isNotBlank() && validIdentifier && validProtocol &&
                read.dataLength in 1..maxDataLength && read.intervalMs in 100..60_000 && read.timeoutMs in 100..45_000 &&
                read.signals.isNotEmpty() && read.signals.size <= 128 &&
                read.signals.all { it.validFor(read.dataLength) } &&
                read.signals.map { it.id }.distinct().size == read.signals.size
            if (!valid) {
                add("Définition invalide : ${read.id}")
                continue
            }
            if (read.examples.isEmpty() || read.examples.size > 256) add("Rejeux de référence manquants ou trop nombreux : ${read.id}")
            for (example in read.examples.take(256)) {
                val decoded = runCatching { read.decodePayload(example.responsePayloadHex) }.getOrNull()
                val validExample = decoded != null &&
                    decoded.map { it.id }.toSet() == example.expectedValues.keys &&
                    decoded.all { signal ->
                        val expected = example.expectedValues.getValue(signal.id)
                        val value = signal.value
                        if (expected == null) value == null else
                            value != null && expected.isFinite() && abs(value - expected) <= 1e-9 * max(1.0, abs(expected))
                    }
                if (!validExample) add("Rejeu de référence incorrect : ${read.id}")
            }
        }
    }

    companion object {
        /** Catalogue de production vide tant qu'aucune définition constructeur n'est validée. */
        val installed = ManufacturerProfiles(emptyList())

        private fun validIdentity(identity: EcuFingerprint): Boolean {
            val address = when (identity.endpoint.protocol) {
                ProfileProtocol.KWP_FAST, ProfileProtocol.KWP_SLOW -> Regex("[0-9A-F]{2}")
                ProfileProtocol.CAN_11 -> Regex("[0-7][0-9A-F]{2}")
                ProfileProtocol.CAN_29 -> Regex("[01][0-9A-F]{7}")
            }
            if (!address.matches(identity.endpoint.requestAddress) || !address.matches(identity.endpoint.responseAddress)) return false
            if (identity.scheme.isBlank() || identity.fields.isEmpty()) return false
            // VIN, modèle ou marque seuls ne constituent pas une identité logicielle ECU.
            if (identity.fields.keys.all { it.lowercase(Locale.ROOT) in setOf("vin", "model", "brand", "year") }) return false
            if (identity.fields.any { it.key.isBlank() || it.value.isBlank() || '*' in it.value || it.value == "?" }) return false
            if (identity.scheme == "renault_std_a_candidate") {
                return identity.fields.keys == setOf("diagnostic_version", "supplier", "software", "version") &&
                    identity.fields.getValue("diagnostic_version").let { text ->
                        text.toIntOrNull()?.let { it in 0..255 && it.toString() == text } == true
                    } &&
                    identity.fields.getValue("supplier").let { it.length == 3 && it.all { c -> c in ' '..'~' } } &&
                    identity.fields.getValue("software").matches(Regex("[0-9A-F]{4}")) &&
                    identity.fields.getValue("version").matches(Regex("[0-9A-F]{4}"))
                    && identity.endpoint.protocol in setOf(ProfileProtocol.KWP_FAST, ProfileProtocol.KWP_SLOW)
            }
            return true
        }
    }
}

internal fun strictHexBytes(hex: String): List<Int> {
    require(hex.length <= 12_288 && hex.all { it in "0123456789abcdefABCDEF \t" }) { "Trame hexadécimale invalide" }
    val trimmed = hex.trim(' ', '\t')
    require(trimmed.isNotEmpty()) { "Trame hexadécimale vide" }
    val tokens = if (trimmed.any { it == ' ' || it == '\t' }) {
        trimmed.split(Regex("[ \\t]+"))
    } else {
        trimmed.chunked(2)
    }
    require(tokens.all { it.length == 2 }) { "Octet hexadécimal incomplet" }
    return tokens.map { it.toInt(16) }
}
