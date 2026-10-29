package io.github.anastaci1a.localica

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Process
import android.provider.DocumentsContract
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructStat
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener

/**
 * Fixed private host-authorization interface, NOT host attestation. The external lead harness must
 * independently establish fresh container/AVD/package absence, exact installed bytes, exclusive
 * ownership, continuity and the measured pre-copy peak ledger before provisioning these files.
 * Digest references and parsed declarations do not prove any of those past host observations.
 * DUMP protects the separate Activity but is not shell-only authentication. No device permission,
 * shell identity, policy, installation or transfer is changed here.
 *
 * The serving process must own this test APK's filesDir. The host creates a fresh private base and
 * control directory, completes its bounded transfer and same-filesystem move to an absent corpus
 * destination, and removes the now-empty pending slot before PREPARE. This code never copies,
 * renames, clears, repairs or adopts a corpus. Control/pin/receipt files stay OUTSIDE the exact root.
 *
 * Exclusive markers precede effects. Any partial marker/pin/receipt, duplicate request or restart
 * after consumption requires external reconciliation; deleting markers is not a supported reset.
 * Restart never auto-prepares from durable files. No restart reconciliation protocol is implemented.
 */
internal object NavigationRandomCorpusOwnedRoot {
    const val PROVIDER_PACKAGE = "io.github.anastaci1a.localica.debug.test"
    const val TARGET_PACKAGE = "io.github.anastaci1a.localica.debug"
    const val FROZEN_MANIFEST_SHA256 = "f71c0e0f6abe9edbd5e3b9f048b2e827c0acffa57c903f42f558c41abef27e3a"
    const val ACTION_PREPARE = "io.github.anastaci1a.localica.test.navigation.random.PREPARE"
    const val ACTION_GRANT = "io.github.anastaci1a.localica.test.navigation.random.GRANT"
    const val ACTION_REVOKE = "io.github.anastaci1a.localica.test.navigation.random.REVOKE"
    const val MAX_CONTROL_BYTES = 64 * 1024
    const val MAX_RECEIPT_BYTES = 16 * 1024
    const val BASE_DIRECTORY = "navigation-random-v1"
    private const val MAX_APK_BYTES = 256L * 1024 * 1024
    private val SHA256 = Regex("[0-9a-f]{64}")
    private val ID = Regex("[0-9a-f]{32}")
    private val RUN_ID = Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,127}")

    enum class Category {
        ControlTooLarge, MalformedControl, ForeignControl, ContextUidMismatch, LocalIdentityMismatch,
        UnsafePrivatePath, ActionAlreadyConsumed, PinMismatch, ProviderNotReady, VerificationFailed,
        GrantFailed, RevokeFailed, UnsupportedPlatformFacts, ReceiptWriteFailed, UnknownAction,
    }

    class Failure(val category: Category, message: String, cause: Throwable? = null) : IllegalArgumentException(message, cause)

    data class PackageObservation(
        val packageName: String, val uid: Int, val firstInstallMs: Long, val lastUpdateMs: Long,
        val versionCode: Long, val apkSha256: String, val signerSha256: String,
    )

    data class ControlDeclaration(
        val action: String, val authorizationId: String, val admissionId: String, val runId: String,
        val attempt: Int, val api: Int, val manifestSha256: String,
        val provider: PackageObservation, val target: PackageObservation,
        val hostOwnershipReceiptSha256: String, val preCopyBudgetReceiptSha256: String,
        val stagedCopyReceiptSha256: String,
    ) {
        val evidenceScope: String = "control-declarations-only"
    }

    data class Completion(
        val status: String, val category: String, val authorizationId: String?,
        val admissionId: String? = null, val attemptedTrees: List<String> = emptyList(),
        val completedTrees: List<String> = emptyList(), val provider: PackageObservation? = null,
        val target: PackageObservation? = null, val failureClass: String? = null,
    )

    data class CompletionDeclaration(val action: String, val status: String, val category: String,
        val authorizationId: String?, val admissionId: String?) {
        val evidenceScope: String = "completion-declarations-only"
    }

    private val CONTROL_KEYS = setOf("schema_version", "action", "authorization_id", "admission_id", "run_id", "attempt", "api",
        "manifest_sha256", "provider_package", "provider_uid", "provider_first_install_ms", "provider_last_update_ms",
        "provider_version_code", "provider_apk_sha256", "provider_signer_sha256", "target_package", "target_uid",
        "target_first_install_ms", "target_last_update_ms", "target_version_code", "target_apk_sha256", "target_signer_sha256",
        "host_ownership_receipt_sha256", "pre_copy_budget_receipt_sha256", "staged_copy_receipt_sha256")
    private val RECEIPT_KEYS = setOf("schema_version", "action", "authorization_id", "admission_id", "status", "category",
        "manifest_sha256", "host_evidence_verified_by_app", "target_persisted_readback_verified", "attempted_trees",
        "completed_trees", "observed_provider", "observed_target", "failure_class", "device_time_ms")
    private val STATUSES = setOf("prepared_local_only", "grants_offered_not_taken", "partial_grant_failure",
        "revocation_requested", "partial_revoke_failure", "failed_closed", "receipt_write_failed_effects_possible")
    private val TREE_URIS = listOf(NavigationRandomCorpusDocumentsProvider.ROOT, NavigationRandomCorpusDocumentsProvider.OVERLAP)
        .map { DocumentsContract.buildTreeDocumentUri(NavigationRandomCorpusDocumentsProvider.AUTHORITY, it).toString() }

    /** Compact canonical JSON only: no pretty whitespace, trailing LF, duplicate keys or coercion. */
    fun parseControl(bytes: ByteArray): ControlDeclaration {
        val json = parseObject(bytes, MAX_CONTROL_BYTES)
        keys(json, CONTROL_KEYS)
        shape(integer(json, "schema_version") == 1L, "Unsupported control schema")
        val action = string(json, "action")
        if (action !in setOf("prepare", "grant", "revoke")) reject(Category.UnknownAction, "Unknown control action")
        val authorizationId = string(json, "authorization_id").also { shape(ID.matches(it), "Invalid authorization ID") }
        val admissionId = string(json, "admission_id").also { shape(ID.matches(it), "Invalid admission ID") }
        val runId = string(json, "run_id").also { shape(RUN_ID.matches(it), "Invalid run ID") }
        val attempt = integer(json, "attempt").also { shape(it in 1..Int.MAX_VALUE.toLong(), "Invalid attempt") }.toInt()
        val api = integer(json, "api")
        if (api != 26L && api != 36L) reject(Category.ForeignControl, "Unsupported admitted API")
        val manifest = hash(json, "manifest_sha256")
        if (manifest != FROZEN_MANIFEST_SHA256) reject(Category.ForeignControl, "Foreign frozen manifest")
        val provider = packageDeclaration(json, "provider_")
        val target = packageDeclaration(json, "target_")
        if (provider.packageName != PROVIDER_PACKAGE || target.packageName != TARGET_PACKAGE)
            reject(Category.ForeignControl, "Foreign package declaration")
        shape(provider.uid != target.uid, "Provider and target must have distinct UIDs")
        return ControlDeclaration(action, authorizationId, admissionId, runId, attempt, api.toInt(), manifest, provider, target,
            hash(json, "host_ownership_receipt_sha256"), hash(json, "pre_copy_budget_receipt_sha256"), hash(json, "staged_copy_receipt_sha256"))
    }

    /** Shape inspection only; a parsed receipt is not proof that this app executed its contents. */
    fun parseReceipt(bytes: ByteArray): CompletionDeclaration {
        val json = parseObject(bytes, MAX_RECEIPT_BYTES)
        keys(json, RECEIPT_KEYS)
        shape(integer(json, "schema_version") == 1L, "Unsupported receipt schema")
        val action = string(json, "action")
        shape(action in setOf("prepare", "grant", "revoke"), "Invalid receipt action")
        val status = string(json, "status").also { shape(it in STATUSES, "Invalid completion status") }
        val category = string(json, "category").also { shape(it == "none" || Category.values().any { value -> value.name == it }, "Invalid failure category") }
        val auth = nullableString(json, "authorization_id")?.also { shape(ID.matches(it), "Invalid receipt authorization ID") }
        val admission = nullableString(json, "admission_id")?.also { shape(ID.matches(it), "Invalid receipt admission ID") }
        shape(string(json, "manifest_sha256") == FROZEN_MANIFEST_SHA256, "Foreign receipt manifest")
        shape(value(json, "host_evidence_verified_by_app") == false && value(json, "target_persisted_readback_verified") == false,
            "Receipt must not claim external gates")
        val attempted = treeList(json, "attempted_trees")
        val completed = treeList(json, "completed_trees")
        shape(completed.all { it in attempted }, "Completion without an attempted tree")
        for (key in listOf("observed_provider", "observed_target")) {
            val observed = value(json, key)
            if (observed !== JSONObject.NULL) {
                shape(observed is JSONObject, "Invalid local observation")
                keys(observed as JSONObject, PACKAGE_KEYS)
                val packageValue = packageDeclaration(observed, "")
                shape(packageValue.packageName == if (key == "observed_provider") PROVIDER_PACKAGE else TARGET_PACKAGE, "Foreign local observation")
            }
        }
        nullableString(json, "failure_class")?.let { shape(it.length in 1..255 && it.all { ch -> ch.isLetterOrDigit() || ch in ".$" }, "Invalid failure class") }
        shape(integer(json, "device_time_ms") >= 0L, "Invalid observed device clock")
        return CompletionDeclaration(action, status, category, auth, admission)
    }

    fun isFixedRequest(intent: Intent): Boolean = intent.action in setOf(ACTION_PREPARE, ACTION_GRANT, ACTION_REVOKE) &&
        intent.data == null && intent.type == null && intent.clipData == null && intent.extras == null &&
        intent.categories.isNullOrEmpty() && (intent.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or
        Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)) == 0

    /** Current process observation only; targetContext observations are not provider-UID evidence. */
    fun observeOwn(context: Context): PackageObservation {
        if (context.applicationInfo.uid != Process.myUid()) reject(Category.ContextUidMismatch, "Context does not belong to executing UID")
        val observed = observePackage(context.packageManager, context.packageName)
        if (observed.first.uid != Process.myUid() || observed.second != context.applicationInfo.sourceDir)
            reject(Category.ContextUidMismatch, "Executing context/package binding differs")
        return observed.first
    }

    /** Called only by the protected fixed-action Activity, off-main. No host fact becomes app proof. */
    @Synchronized
    fun execute(context: Context, action: String): Completion {
        val wire = when (action) { ACTION_PREPARE -> "prepare"; ACTION_GRANT -> "grant"; ACTION_REVOKE -> "revoke"
            else -> return Completion("failed_closed", Category.UnknownAction.name, null) }
        var paths: Paths? = null
        var declaration: ControlDeclaration? = null
        var providerObservation: PackageObservation? = null
        var targetObservation: PackageObservation? = null
        var consumed = false
        val attempted = ArrayList<String>()
        val completed = ArrayList<String>()
        val completion = try {
            val activePaths = privatePaths(context)
            paths = activePaths
            if (exists(activePaths.used(wire)) || exists(activePaths.receipt(wire))) reject(Category.ActionAlreadyConsumed, "Action already consumed; reconciliation required")
            val bytes = readPrivate(activePaths.authorization(wire), MAX_CONTROL_BYTES, Process.myUid())
            val control = parseControl(bytes)
            declaration = control
            if (control.action != wire) reject(Category.ForeignControl, "Authorization is for another action")
            for (other in setOf("prepare", "grant", "revoke") - wire) {
                if (exists(activePaths.used(other))) {
                    val used = parseControl(readPrivate(activePaths.used(other), MAX_CONTROL_BYTES, Process.myUid()))
                    if (used.action != other) reject(Category.PinMismatch, "Consumed action record differs")
                    if (used.authorizationId == control.authorizationId) reject(Category.ActionAlreadyConsumed, "Authorization ID was already consumed")
                }
            }
            // O_EXCL precedes every effect. A partial write remains a blocker, never a reusable ticket.
            exclusiveWrite(activePaths.used(wire), bytes)
            consumed = true
            providerObservation = observeOwn(context)
            targetObservation = observePackage(context.packageManager, TARGET_PACKAGE).first
            if (control.api != Build.VERSION.SDK_INT || control.provider != providerObservation || control.target != targetObservation)
                reject(Category.LocalIdentityMismatch, "Observed install/APK identities disagree with authorization")
            val pin = pinBytes(control)
            if (wire == "prepare") {
                if (exists(activePaths.pin)) reject(Category.PinMismatch, "An installation pin already exists; no adoption/replay")
                exclusiveWrite(activePaths.pin, pin)
            } else if (!readPrivate(activePaths.pin, MAX_CONTROL_BYTES, Process.myUid()).contentEquals(pin)) {
                reject(Category.PinMismatch, "Installation/corpus pin differs")
            }
            if (wire != "revoke") {
                if (exists(activePaths.pending)) reject(Category.UnsafePrivatePath, "Pending slot exists; never activate a copied prefix")
                privateDirectory(activePaths.corpus, Process.myUid())
            }
            when (wire) {
                "prepare" -> withProvider(context) { provider ->
                    val manifest = readRegular(File(activePaths.corpus, "manifest.json"), NavigationRandomCorpusJson.MAX_MANIFEST_BYTES.toLong(), true, Process.myUid()).second!!
                    val tracks = readRegular(File(activePaths.corpus, "tracks.jsonl"), NavigationRandomCorpusJson.MAX_TRACKS_BYTES.toLong(), true, Process.myUid()).second!!
                    val binding = NavigationRandomCorpusJson.parse(manifest, tracks, FROZEN_MANIFEST_SHA256, NavigationRandomCorpusDocumentsProvider.AUTHORITY)
                    requireCorpusOwnership(activePaths.corpus, binding)
                    provider.prepare(activePaths.corpus, binding)
                    provider.verifyReadyPhysicalBinding(activePaths.corpus, FROZEN_MANIFEST_SHA256)
                }
                "grant" -> {
                    val preparedControl = parseControl(readPrivate(activePaths.used("prepare"), MAX_CONTROL_BYTES, Process.myUid()))
                    val preparedReceipt = parseReceipt(readPrivate(activePaths.receipt("prepare"), MAX_RECEIPT_BYTES, Process.myUid()))
                    if (preparedControl.action != "prepare" || !pinBytes(preparedControl).contentEquals(pin) ||
                        preparedReceipt.action != "prepare" || preparedReceipt.status != "prepared_local_only" || preparedReceipt.category != "none" ||
                        preparedReceipt.authorizationId != preparedControl.authorizationId || preparedReceipt.admissionId != control.admissionId)
                        reject(Category.ProviderNotReady, "No complete matching preparation receipt")
                    withProvider(context) {
                        try { it.verifyReadyPhysicalBinding(activePaths.corpus, FROZEN_MANIFEST_SHA256) }
                        catch (failure: Exception) { reject(Category.ProviderNotReady, "Prepared in-memory provider unavailable; no restart adoption", failure) }
                    }
                    for (tree in TREE_URIS) {
                        attempted += tree
                        context.grantUriPermission(TARGET_PACKAGE, Uri.parse(tree), Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            Intent.FLAG_GRANT_PREFIX_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                        completed += tree
                    }
                }
                "revoke" -> {
                    if (!exists(activePaths.used("grant"))) reject(Category.ProviderNotReady, "No owned grant attempt to reconcile")
                    for (tree in TREE_URIS) {
                        attempted += tree
                        context.revokeUriPermission(TARGET_PACKAGE, Uri.parse(tree), Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        completed += tree
                    }
                }
            }
            Completion(when (wire) { "prepare" -> "prepared_local_only"; "grant" -> "grants_offered_not_taken"; else -> "revocation_requested" },
                "none", control.authorizationId, control.admissionId, attempted.toList(), completed.toList(), providerObservation, targetObservation)
        } catch (failure: Exception) {
            val category = (failure as? Failure)?.category ?: when (wire) {
                "prepare" -> Category.VerificationFailed; "grant" -> Category.GrantFailed; else -> Category.RevokeFailed
            }
            val status = when { wire == "grant" && attempted.isNotEmpty() -> "partial_grant_failure"
                wire == "revoke" && attempted.isNotEmpty() -> "partial_revoke_failure"; else -> "failed_closed" }
            Completion(status, category.name, declaration?.authorizationId, declaration?.admissionId, attempted.toList(), completed.toList(),
                providerObservation, targetObservation, failure.javaClass.name)
        }
        // Missing/invalid authorization never creates a receipt that could be mistaken for a consumed command.
        if (consumed && paths != null) {
            try { exclusiveWrite(paths.receipt(wire), receiptBytes(wire, completion)) }
            // A failed receipt write cannot roll back a publication or grants already offered.
            catch (failure: Exception) { return completion.copy(status = "receipt_write_failed_effects_possible",
                category = Category.ReceiptWriteFailed.name, failureClass = failure.javaClass.name) }
        }
        return completion
    }

    private data class Paths(val base: File, val control: File, val pending: File, val corpus: File) {
        val pin: File get() = File(control, "install-pin.json")
        fun authorization(action: String) = File(control, "$action.authorization.json")
        fun used(action: String) = File(control, "$action.used.json")
        fun receipt(action: String) = File(control, "$action.receipt.json")
    }

    private fun privatePaths(context: Context): Paths {
        if (context.packageName != PROVIDER_PACKAGE || context.applicationInfo.uid != Process.myUid())
            reject(Category.ContextUidMismatch, "Only the serving test-APK UID may use private control paths")
        // Resolve only the framework-supplied app root (which may have a platform alias), never a
        // caller path. Fixed descendants must still be canonical and contain no symlink ancestors.
        val files = context.filesDir.canonicalFile
        val filesStat = Os.lstat(files.path)
        if (!files.isAbsolute || !OsConstants.S_ISDIR(filesStat.st_mode) || filesStat.st_uid != Process.myUid())
            reject(Category.UnsafePrivatePath, "Framework files directory does not belong to serving UID")
        val base = File(files, BASE_DIRECTORY)
        val control = File(base, "control")
        privateDirectory(base, Process.myUid())
        privateDirectory(control, Process.myUid())
        return Paths(base, control, File(base, "pending"), File(base, "corpus"))
    }

    /** Local UID/type observations only; external exclusive ownership must remain continuous. */
    private fun requireCorpusOwnership(root: File, binding: NavigationRandomCorpusBinding) {
        val relativeFiles = linkedSetOf("manifest.json", "tracks.jsonl")
        for (track in binding.tracks) {
            relativeFiles += track.relativeMp3Path
            relativeFiles += track.coverRelativePath
        }
        val directories = HashSet<File>()
        for (relative in relativeFiles) {
            val file = File(root, relative)
            val stat = Os.lstat(file.path)
            if (!OsConstants.S_ISREG(stat.st_mode) || stat.st_nlink != 1L || stat.st_uid != Process.myUid())
                reject(Category.UnsafePrivatePath, "Corpus file is not an unaliased serving-UID regular file")
            var directory = file.parentFile
            while (directory != null && directory != root) {
                if (!directory.path.startsWith(root.path + File.separator)) reject(Category.UnsafePrivatePath, "Corpus parent escaped root")
                directories += directory
                directory = directory.parentFile
            }
        }
        for (directory in directories) {
            val stat = Os.lstat(directory.path)
            if (!OsConstants.S_ISDIR(stat.st_mode) || stat.st_uid != Process.myUid() || directory.path != directory.canonicalPath)
                reject(Category.UnsafePrivatePath, "Corpus directory does not belong to serving UID")
        }
    }

    private fun privateDirectory(file: File, uid: Int) {
        if (!file.isAbsolute || file.path != file.canonicalPath) reject(Category.UnsafePrivatePath, "Private path must be canonical")
        var ancestor: File? = file
        while (ancestor != null) {
            if (!OsConstants.S_ISDIR(Os.lstat(ancestor.path).st_mode)) reject(Category.UnsafePrivatePath, "Symlink/non-directory ancestor")
            ancestor = ancestor.parentFile
        }
        val stat = Os.lstat(file.path)
        if (stat.st_uid != uid || (stat.st_mode and 63) != 0) reject(Category.UnsafePrivatePath, "Private directory must be UID-owned without group/other access")
    }

    private fun readPrivate(file: File, limit: Int, uid: Int): ByteArray {
        val stat = Os.lstat(file.path)
        if (!OsConstants.S_ISREG(stat.st_mode) || stat.st_uid != uid || stat.st_nlink != 1L || (stat.st_mode and 63) != 0)
            reject(Category.UnsafePrivatePath, "Control files must be private unaliased UID-owned regular files")
        return readRegular(file, limit.toLong(), true, uid, privateMode = true).second!!
    }

    private fun exclusiveWrite(file: File, bytes: ByteArray) {
        if (bytes.size > MAX_CONTROL_BYTES) reject(Category.ReceiptWriteFailed, "Private record exceeds bound")
        val fd = Os.open(file.path, OsConstants.O_WRONLY or OsConstants.O_CREAT or OsConstants.O_EXCL or OsConstants.O_NOFOLLOW or OsConstants.O_CLOEXEC, 384)
        var primary: Throwable? = null
        try {
            var offset = 0
            while (offset < bytes.size) {
                val count = Os.write(fd, bytes, offset, bytes.size - offset)
                if (count <= 0) reject(Category.ReceiptWriteFailed, "Private record write made no progress")
                offset += count
            }
            Os.fsync(fd)
        } catch (failure: Throwable) { primary = failure; throw failure }
        finally { try { Os.close(fd) } catch (closing: Throwable) { if (primary == null) throw closing else primary.addSuppressed(closing) } }
        // The consumed filename must be durable before any publication/grant can follow it.
        val directoryFd = Os.open(requireNotNull(file.parentFile).path, OsConstants.O_RDONLY or OsConstants.O_DIRECTORY or OsConstants.O_NOFOLLOW or OsConstants.O_CLOEXEC, 0)
        primary = null
        try { Os.fsync(directoryFd) }
        catch (failure: Throwable) { primary = failure; throw failure }
        finally { try { Os.close(directoryFd) } catch (closing: Throwable) { if (primary == null) throw closing else primary.addSuppressed(closing) } }
    }

    private fun exists(file: File): Boolean = try { Os.lstat(file.path); true }
        catch (failure: ErrnoException) { if (failure.errno == OsConstants.ENOENT) false else throw failure }

    private inline fun <T> withProvider(context: Context, block: (NavigationRandomCorpusDocumentsProvider) -> T): T {
        val info = context.packageManager.resolveContentProvider(NavigationRandomCorpusDocumentsProvider.AUTHORITY, 0)
            ?: reject(Category.ProviderNotReady, "Registered provider unavailable")
        if (info.packageName != PROVIDER_PACKAGE || info.applicationInfo.uid != Process.myUid() ||
            info.readPermission != "android.permission.MANAGE_DOCUMENTS" || !info.grantUriPermissions)
            reject(Category.ProviderNotReady, "Provider registration/UID differs")
        val client = context.contentResolver.acquireContentProviderClient(NavigationRandomCorpusDocumentsProvider.AUTHORITY)
            ?: reject(Category.ProviderNotReady, "Provider client unavailable")
        client.use {
            val provider = client.localContentProvider as? NavigationRandomCorpusDocumentsProvider
                ?: reject(Category.ProviderNotReady, "Bridge and provider must share the serving process")
            return block(provider)
        }
    }

    private fun observePackage(manager: PackageManager, name: String): Pair<PackageObservation, String> {
        try {
            val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
            val before = manager.getPackageInfo(name, flags)
            val app = before.applicationInfo ?: reject(Category.UnsupportedPlatformFacts, "Application metadata unavailable")
            if (!app.splitSourceDirs.isNullOrEmpty()) reject(Category.UnsupportedPlatformFacts, "This bounded schema supports monolithic APKs only")
            val observation = PackageObservation(name, app.uid, before.firstInstallTime, before.lastUpdateTime,
                version(before), readRegular(File(app.sourceDir), MAX_APK_BYTES, false).first, signer(before))
            val after = manager.getPackageInfo(name, flags)
            val afterApp = after.applicationInfo ?: reject(Category.UnsupportedPlatformFacts, "Application metadata unavailable")
            if (afterApp.uid != observation.uid || afterApp.sourceDir != app.sourceDir || !afterApp.splitSourceDirs.isNullOrEmpty() ||
                after.firstInstallTime != observation.firstInstallMs || after.lastUpdateTime != observation.lastUpdateMs ||
                version(after) != observation.versionCode || signer(after) != observation.signerSha256)
                reject(Category.LocalIdentityMismatch, "Package changed during local observation")
            return observation to app.sourceDir
        } catch (failure: Failure) { throw failure }
        catch (failure: Exception) { reject(Category.UnsupportedPlatformFacts, "Installed package/APK observation unavailable", failure) }
    }

    @Suppress("DEPRECATION")
    private fun version(info: PackageInfo): Long = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()

    @Suppress("DEPRECATION")
    private fun signer(info: PackageInfo): String {
        val signers = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
        if (signers == null || signers.size != 1) reject(Category.UnsupportedPlatformFacts, "Exactly one current signer is required")
        return sha256(signers[0].toByteArray())
    }

    private fun sameFile(left: StructStat, right: StructStat): Boolean = left.st_dev == right.st_dev && left.st_ino == right.st_ino &&
        left.st_uid == right.st_uid && left.st_gid == right.st_gid &&
        left.st_mode == right.st_mode && left.st_nlink == right.st_nlink && left.st_size == right.st_size &&
        left.st_mtime == right.st_mtime && left.st_ctime == right.st_ctime

    private fun readRegular(file: File, maxBytes: Long, capture: Boolean, requiredUid: Int? = null, privateMode: Boolean = false): Pair<String, ByteArray?> {
        val before = Os.lstat(file.path)
        if (!OsConstants.S_ISREG(before.st_mode) || before.st_nlink != 1L || before.st_size < 0L)
            reject(Category.UnsafePrivatePath, "Require an unaliased regular file")
        if ((requiredUid != null && before.st_uid != requiredUid) || (privateMode && (before.st_mode and 63) != 0))
            reject(Category.UnsafePrivatePath, "Opened private file ownership/mode differs")
        if (before.st_size > maxBytes) reject(Category.ControlTooLarge, "File exceeds read bound")
        val fd = Os.open(file.path, OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW or OsConstants.O_CLOEXEC or OsConstants.O_NONBLOCK, 0)
        var primary: Throwable? = null
        try {
            if (!sameFile(before, Os.fstat(fd))) reject(Category.UnsafePrivatePath, "Opened file changed")
            val digest = MessageDigest.getInstance("SHA-256")
            val output = if (capture) ByteArrayOutputStream(minOf(before.st_size, 65536L).toInt()) else null
            val buffer = ByteArray(65536)
            var count = 0L
            while (true) {
                val length = Os.read(fd, buffer, 0, buffer.size)
                if (length == 0) break
                if (length < 0 || length.toLong() > maxBytes - count) reject(Category.ControlTooLarge, "Read exceeds bound")
                count += length
                digest.update(buffer, 0, length)
                output?.write(buffer, 0, length)
            }
            if (count != before.st_size || !sameFile(before, Os.fstat(fd)) || !sameFile(before, Os.lstat(file.path)))
                reject(Category.UnsafePrivatePath, "File changed or read was incomplete")
            return hex(digest.digest()) to output?.toByteArray()
        } catch (failure: Throwable) { primary = failure; throw failure }
        finally { try { Os.close(fd) } catch (closing: Throwable) { if (primary == null) throw closing else primary.addSuppressed(closing) } }
    }

    private fun pinBytes(declaration: ControlDeclaration): ByteArray = controlJson(declaration).apply {
        remove("action"); remove("authorization_id")
    }.toString().toByteArray(Charsets.UTF_8)

    private fun controlJson(value: ControlDeclaration): JSONObject = JSONObject().put("schema_version", 1)
        .put("action", value.action).put("authorization_id", value.authorizationId).put("admission_id", value.admissionId)
        .put("run_id", value.runId).put("attempt", value.attempt).put("api", value.api).put("manifest_sha256", value.manifestSha256)
        .also { packageJson(it, "provider_", value.provider); packageJson(it, "target_", value.target) }
        .put("host_ownership_receipt_sha256", value.hostOwnershipReceiptSha256).put("pre_copy_budget_receipt_sha256", value.preCopyBudgetReceiptSha256)
        .put("staged_copy_receipt_sha256", value.stagedCopyReceiptSha256)

    private fun receiptBytes(action: String, completion: Completion): ByteArray {
        val json = JSONObject().put("schema_version", 1).put("action", action)
            .put("authorization_id", completion.authorizationId ?: JSONObject.NULL).put("admission_id", completion.admissionId ?: JSONObject.NULL)
            .put("status", completion.status).put("category", completion.category).put("manifest_sha256", FROZEN_MANIFEST_SHA256)
            .put("host_evidence_verified_by_app", false).put("target_persisted_readback_verified", false)
            .put("attempted_trees", JSONArray(completion.attemptedTrees)).put("completed_trees", JSONArray(completion.completedTrees))
            .put("observed_provider", completion.provider?.let { packageJson(JSONObject(), "", it) } ?: JSONObject.NULL)
            .put("observed_target", completion.target?.let { packageJson(JSONObject(), "", it) } ?: JSONObject.NULL)
            .put("failure_class", completion.failureClass ?: JSONObject.NULL).put("device_time_ms", System.currentTimeMillis())
        val bytes = json.toString().toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_RECEIPT_BYTES) reject(Category.ReceiptWriteFailed, "Completion receipt exceeds bound")
        parseReceipt(bytes)
        return bytes
    }

    private val PACKAGE_KEYS = setOf("package", "uid", "first_install_ms", "last_update_ms", "version_code", "apk_sha256", "signer_sha256")
    private fun packageJson(json: JSONObject, prefix: String, value: PackageObservation): JSONObject = json
        .put(prefix + "package", value.packageName).put(prefix + "uid", value.uid).put(prefix + "first_install_ms", value.firstInstallMs)
        .put(prefix + "last_update_ms", value.lastUpdateMs).put(prefix + "version_code", value.versionCode)
        .put(prefix + "apk_sha256", value.apkSha256).put(prefix + "signer_sha256", value.signerSha256)

    private fun packageDeclaration(json: JSONObject, prefix: String): PackageObservation {
        val uid = integer(json, prefix + "uid").also { shape(it in 10000..Int.MAX_VALUE.toLong(), "Invalid application UID") }.toInt()
        val first = integer(json, prefix + "first_install_ms").also { shape(it > 0L, "Invalid first install time") }
        val last = integer(json, prefix + "last_update_ms").also { shape(it >= first, "Invalid update time") }
        val version = integer(json, prefix + "version_code").also { shape(it >= 0L, "Invalid version code") }
        return PackageObservation(string(json, prefix + "package"), uid, first, last, version,
            hash(json, prefix + "apk_sha256"), hash(json, prefix + "signer_sha256"))
    }

    private fun parseObject(bytes: ByteArray, limit: Int): JSONObject {
        if (bytes.size > limit) reject(Category.ControlTooLarge, "JSON byte bound exceeded")
        shape(bytes.isNotEmpty(), "Empty control/receipt")
        val text = try { Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes.copyOf())).toString() }
        catch (failure: CharacterCodingException) { reject(Category.MalformedControl, "Invalid UTF-8", failure) }
        shape('\u0000' !in text, "Literal NUL is forbidden")
        try {
            val value = JSONTokener(text).nextValue()
            shape(value is JSONObject, "Require an object")
            val result = value as JSONObject
            // Android JSONTokener is lenient; exact compact re-encoding removes that acceptance surface.
            shape(result.toString() == text, "Require compact canonical JSON without duplicate keys/trailing content")
            return result
        } catch (failure: JSONException) { reject(Category.MalformedControl, "Malformed JSON", failure) }
    }

    private fun keys(json: JSONObject, expected: Set<String>) {
        val actual = HashSet<String>(); val iterator = json.keys(); while (iterator.hasNext()) actual += iterator.next()
        shape(actual == expected, "Missing/unknown JSON keys")
    }
    private fun value(json: JSONObject, key: String): Any = try { json.get(key) }
        catch (failure: JSONException) { reject(Category.MalformedControl, "Missing field: $key", failure) }
    private fun string(json: JSONObject, key: String): String = value(json, key).let { shape(it is String, "Require string: $key"); it as String }
    private fun nullableString(json: JSONObject, key: String): String? = value(json, key).let {
        if (it === JSONObject.NULL) null else { shape(it is String, "Require nullable string: $key"); it as String }
    }
    private fun integer(json: JSONObject, key: String): Long = when (val value = value(json, key)) {
        is Int -> value.toLong(); is Long -> value; else -> reject(Category.MalformedControl, "Require Int/Long: $key")
    }
    private fun hash(json: JSONObject, key: String): String = string(json, key).also { shape(SHA256.matches(it), "Require lowercase SHA256: $key") }
    private fun treeList(json: JSONObject, key: String): List<String> {
        val array = value(json, key); shape(array is JSONArray, "Require tree array")
        val result = ArrayList<String>()
        for (index in 0 until (array as JSONArray).length()) {
            val entry = array.get(index); shape(entry is String && entry in TREE_URIS, "Foreign grant tree")
            result += entry as String
        }
        shape(result.size == result.distinct().size, "Duplicate grant tree")
        return result
    }
    private fun shape(condition: Boolean, message: String) { if (!condition) reject(Category.MalformedControl, message) }
    private fun sha256(bytes: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(bytes))
    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
    private fun reject(category: Category, message: String, cause: Throwable? = null): Nothing = throw Failure(category, message, cause)
}
