package io.github.anastaci1a.localica

import android.Manifest
import android.content.ClipData
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.provider.DocumentsContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/** Declaration parsing and negative permission contracts only; never creates admission evidence. */
@RunWith(AndroidJUnit4::class)
class NavigationRandomCorpusAdmissionTest {
    @Test
    fun canonicalPrepareGrantAndRevokeParseOnlyAsDeclarations() {
        for (action in listOf("prepare", "grant", "revoke")) {
            val parsed = NavigationRandomCorpusOwnedRoot.parseControl(bytes(control().put("action", action)))
            assertEquals("control-declarations-only", parsed.evidenceScope)
            assertEquals(action, parsed.action)
            assertEquals("a".repeat(32), parsed.authorizationId)
            assertEquals("b".repeat(32), parsed.admissionId)
            assertEquals("source-test-1", parsed.runId)
            assertEquals(1, parsed.attempt)
            assertEquals(26, parsed.api)
            assertEquals(NavigationRandomCorpusOwnedRoot.FROZEN_MANIFEST_SHA256, parsed.manifestSha256)
            assertEquals(NavigationRandomCorpusOwnedRoot.PROVIDER_PACKAGE, parsed.provider.packageName)
            assertEquals(10_001, parsed.provider.uid)
            assertEquals(1L, parsed.provider.firstInstallMs)
            assertEquals(2L, parsed.provider.lastUpdateMs)
            assertEquals(0L, parsed.provider.versionCode)
            assertEquals(hash(1), parsed.provider.apkSha256)
            assertEquals(hash(2), parsed.provider.signerSha256)
            assertEquals(NavigationRandomCorpusOwnedRoot.TARGET_PACKAGE, parsed.target.packageName)
            assertEquals(10_002, parsed.target.uid)
            assertEquals(hash(5), parsed.hostOwnershipReceiptSha256)
            assertEquals(hash(6), parsed.preCopyBudgetReceiptSha256)
            assertEquals(hash(7), parsed.stagedCopyReceiptSha256)
        }
        assertEquals(36, NavigationRandomCorpusOwnedRoot.parseControl(bytes(control().put("api", 36))).api)
    }

    @Test
    fun byteBoundsAndMalformedUtf8UseExactFailureCategories() {
        assertEquals(65_536, NavigationRandomCorpusOwnedRoot.MAX_CONTROL_BYTES)
        assertEquals(16_384, NavigationRandomCorpusOwnedRoot.MAX_RECEIPT_BYTES)
        failure(NavigationRandomCorpusOwnedRoot.Category.ControlTooLarge) {
            NavigationRandomCorpusOwnedRoot.parseControl(ByteArray(NavigationRandomCorpusOwnedRoot.MAX_CONTROL_BYTES + 1))
        }
        for (raw in listOf(byteArrayOf(), byteArrayOf(0xc3.toByte()), byteArrayOf(0xc0.toByte(), 0xaf.toByte()))) {
            failure(NavigationRandomCorpusOwnedRoot.Category.MalformedControl) { NavigationRandomCorpusOwnedRoot.parseControl(raw) }
        }
    }

    @Test
    fun onlyCanonicalCompactObjectEncodingIsAccepted() {
        val canonical = control().toString()
        val malformed = listOf("[]", "null", "{", canonical + "\n", " " + canonical, canonical + "{}",
            canonical + "/* comment */", canonical + '\u0000', canonical.replace("\"schema_version\":1", "\"schema_version\":1.0"),
            canonical.replace("\"schema_version\":1", "\"schema_version\":1,\"schema_version\":1"),
            canonical.replace("\"action\":\"prepare\"", "\"action\":'prepare'"))
        for (text in malformed) failure(NavigationRandomCorpusOwnedRoot.Category.MalformedControl) {
            NavigationRandomCorpusOwnedRoot.parseControl(text.toByteArray(Charsets.UTF_8))
        }
    }

    @Test
    fun everyKeyIsRequiredAndUnknownKeysAreRejected() {
        val keys = control().keys().asSequence().toList()
        assertEquals(25, keys.size)
        for (key in keys) {
            failure(NavigationRandomCorpusOwnedRoot.Category.MalformedControl) {
                NavigationRandomCorpusOwnedRoot.parseControl(bytes(control().also { it.remove(key) }))
            }
        }
        failure(NavigationRandomCorpusOwnedRoot.Category.MalformedControl) {
            NavigationRandomCorpusOwnedRoot.parseControl(bytes(control().put("ready", true)))
        }
    }

    @Test
    fun stringsBooleansAndNullCannotCoerceRequiredScalarTypes() {
        val numeric = listOf("schema_version", "attempt", "api", "provider_uid", "provider_first_install_ms",
            "provider_last_update_ms", "provider_version_code", "target_uid", "target_first_install_ms",
            "target_last_update_ms", "target_version_code")
        for (key in numeric) {
            for (bad in listOf<Any>("1", true, JSONObject.NULL)) malformed(control().put(key, bad))
        }
        for (key in control().keys().asSequence().toList().filterNot { it in numeric }) {
            for (bad in listOf<Any>(1, false, JSONObject.NULL)) malformed(control().put(key, bad))
        }
        val text = control().toString().replace("\"attempt\":1", "\"attempt\":1.0")
        failure(NavigationRandomCorpusOwnedRoot.Category.MalformedControl) {
            NavigationRandomCorpusOwnedRoot.parseControl(text.toByteArray(Charsets.UTF_8))
        }
    }

    @Test
    fun invalidIdsHashesRangesAndInstallOrderingAreNotDeclarations() {
        for (key in listOf("authorization_id", "admission_id")) {
            for (bad in listOf("", "a".repeat(31), "a".repeat(33), "A".repeat(32), "g".repeat(32))) malformed(control().put(key, bad))
        }
        for (run in listOf("", "-prefix", "../escape", "a b", "é", "a".repeat(129))) malformed(control().put("run_id", run))
        for (key in listOf("provider_apk_sha256", "provider_signer_sha256", "target_apk_sha256", "target_signer_sha256",
            "host_ownership_receipt_sha256", "pre_copy_budget_receipt_sha256", "staged_copy_receipt_sha256")) {
            for (bad in listOf("", "a".repeat(63), "A".repeat(64), "g".repeat(64))) malformed(control().put(key, bad))
        }
        for (value in listOf(0L, -1L, Int.MAX_VALUE.toLong() + 1)) malformed(control().put("attempt", value))
        for (key in listOf("provider_uid", "target_uid")) {
            for (value in listOf(9_999L, Int.MAX_VALUE.toLong() + 1)) malformed(control().put(key, value))
        }
        malformed(control().put("target_uid", 10_001))
        for (side in listOf("provider", "target")) {
            malformed(control().put("${side}_first_install_ms", 0))
            malformed(control().put("${side}_last_update_ms", -1))
            malformed(control().put("${side}_first_install_ms", 3))
            malformed(control().put("${side}_version_code", -1))
        }
    }

    @Test
    fun foreignFixedDeclarationsAndUnknownWireActionsHaveDistinctCategories() {
        for ((key, value) in listOf("provider_package" to "other.provider", "target_package" to "other.target",
            "manifest_sha256" to hash(99))) {
            failure(NavigationRandomCorpusOwnedRoot.Category.ForeignControl) {
                NavigationRandomCorpusOwnedRoot.parseControl(bytes(control().put(key, value)))
            }
        }
        for (api in listOf(25, 27, 35, 37)) {
            failure(NavigationRandomCorpusOwnedRoot.Category.ForeignControl) {
                NavigationRandomCorpusOwnedRoot.parseControl(bytes(control().put("api", api)))
            }
        }
        for (action in listOf("", "PREPARE", "activate", NavigationRandomCorpusOwnedRoot.ACTION_PREPARE)) {
            failure(NavigationRandomCorpusOwnedRoot.Category.UnknownAction) {
                NavigationRandomCorpusOwnedRoot.parseControl(bytes(control().put("action", action)))
            }
        }
    }

    @Test
    fun fixedRequestRejectsPayloadsAndGrantFlagsButAllowsNewTask() {
        for (action in listOf(NavigationRandomCorpusOwnedRoot.ACTION_PREPARE, NavigationRandomCorpusOwnedRoot.ACTION_GRANT,
            NavigationRandomCorpusOwnedRoot.ACTION_REVOKE)) {
            assertTrue(NavigationRandomCorpusOwnedRoot.isFixedRequest(Intent(action)))
            assertTrue(NavigationRandomCorpusOwnedRoot.isFixedRequest(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)))
        }
        assertFalse(NavigationRandomCorpusOwnedRoot.isFixedRequest(Intent()))
        assertFalse(NavigationRandomCorpusOwnedRoot.isFixedRequest(Intent("unknown.action")))
        val action = NavigationRandomCorpusOwnedRoot.ACTION_PREPARE
        val invalid = listOf(Intent(action).putExtra("authorization", "not-a-receipt"), Intent(action).putExtras(Bundle()),
            Intent(action).setData(Uri.parse("content://foreign/path")), Intent(action).setType("application/json"),
            Intent(action).addCategory("foreign.category"), Intent(action).apply { clipData = ClipData.newPlainText("control", "not-admission") })
        for (intent in invalid) assertFalse(NavigationRandomCorpusOwnedRoot.isFixedRequest(intent))
        for (flag in listOf(Intent.FLAG_GRANT_READ_URI_PERMISSION, Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION, Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)) {
            assertFalse(NavigationRandomCorpusOwnedRoot.isFixedRequest(Intent(action).addFlags(flag)))
        }
    }

    @Test
    fun ownObservationUsesActualTargetUidAndInstalledApkNotDeclaredProviderIdentity() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals(NavigationRandomCorpusOwnedRoot.TARGET_PACKAGE, target.packageName)
        assertEquals(Process.myUid(), target.applicationInfo.uid)
        val observation = NavigationRandomCorpusOwnedRoot.observeOwn(target)
        assertEquals(target.packageName, observation.packageName)
        assertEquals(Process.myUid(), observation.uid)
        assertEquals(fileDigest(File(target.applicationInfo.sourceDir)), observation.apkSha256)
        val info = target.packageManager.getPackageInfo(target.packageName, 0)
        assertEquals(info.firstInstallTime, observation.firstInstallMs)
        assertEquals(info.lastUpdateTime, observation.lastUpdateMs)
        @Suppress("DEPRECATION")
        val version = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
        assertEquals(version, observation.versionCode)
        assertEquals(installedSignerDigest(target.packageManager, target.packageName), observation.signerSha256)
    }

    @Test
    fun instrumentationPackageContextCannotMasqueradeAsItsDifferentUid() {
        val context = InstrumentationRegistry.getInstrumentation().context
        assertEquals(NavigationRandomCorpusOwnedRoot.PROVIDER_PACKAGE, context.packageName)
        assertTrue("This negative contract requires distinct installed UIDs", context.applicationInfo.uid != Process.myUid())
        failure(NavigationRandomCorpusOwnedRoot.Category.ContextUidMismatch) { NavigationRandomCorpusOwnedRoot.observeOwn(context) }
    }

    @Test
    fun registeredGrantActivityRejectsUnprivilegedTargetBeforePreparation() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals(NavigationRandomCorpusOwnedRoot.TARGET_PACKAGE, target.packageName)
        assertEquals(Process.myUid(), target.applicationInfo.uid)
        assertEquals(PackageManager.PERMISSION_DENIED, target.checkSelfPermission(Manifest.permission.DUMP))
        val component = ComponentName(NavigationRandomCorpusOwnedRoot.PROVIDER_PACKAGE,
            "io.github.anastaci1a.localica.NavigationRandomCorpusGrantActivity")
        // Refuse to launch if registration accidentally dropped its protection.
        val activity = target.packageManager.getActivityInfo(component, 0)
        assertEquals("android.permission.DUMP", activity.permission)
        assertEquals(NavigationRandomCorpusOwnedRoot.PROVIDER_PACKAGE, activity.packageName)
        assertTrue(activity.applicationInfo.uid != Process.myUid())
        val intent = Intent(NavigationRandomCorpusOwnedRoot.ACTION_PREPARE)
            .setComponent(component)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            target.startActivity(intent)
            fail("Unprivileged launch unexpectedly returned; no positive action is authorized")
        } catch (failure: SecurityException) {
            assertTrue("Expected DUMP permission denial: $failure", failure.message.orEmpty().contains("android.permission.DUMP"))
        }
    }

    @Test
    fun registeredProviderRejectsUngrantableTargetQueryAtFrameworkPermissionBoundary() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals(NavigationRandomCorpusOwnedRoot.TARGET_PACKAGE, target.packageName)
        assertEquals(Process.myUid(), target.applicationInfo.uid)
        val authority = NavigationRandomCorpusDocumentsProvider.AUTHORITY
        val provider = requireNotNull(target.packageManager.resolveContentProvider(authority, 0))
        assertEquals(NavigationRandomCorpusOwnedRoot.PROVIDER_PACKAGE, provider.packageName)
        assertEquals("android.permission.MANAGE_DOCUMENTS", provider.readPermission)
        assertTrue(provider.applicationInfo.uid != Process.myUid())
        assertTrue("Existing corpus grant must not be cleared or adopted by this test",
            target.contentResolver.persistedUriPermissions.none { it.uri.authority == authority })
        val tree = DocumentsContract.buildTreeDocumentUri(authority, NavigationRandomCorpusDocumentsProvider.ROOT)
        val uri = DocumentsContract.buildDocumentUriUsingTree(tree, NavigationRandomCorpusDocumentsProvider.ROOT)
        assertEquals(PackageManager.PERMISSION_DENIED, target.checkSelfPermission("android.permission.MANAGE_DOCUMENTS"))
        assertEquals(PackageManager.PERMISSION_DENIED,
            target.checkUriPermission(uri, Process.myPid(), Process.myUid(), Intent.FLAG_GRANT_READ_URI_PERMISSION))
        try {
            target.contentResolver.query(uri, null, null, null, null)?.close()
            fail("Expected framework MANAGE_DOCUMENTS denial, not a provider query")
        } catch (failure: SecurityException) {
            val message = failure.message.orEmpty()
            assertTrue("Expected denial opening the registered provider: $failure",
                message.startsWith("Permission Denial: opening provider ${provider.name} from "))
            assertTrue("Expected document-access denial for this calling process: $failure",
                message.endsWith(" (pid=${Process.myPid()}, uid=${Process.myUid()}) requires that you obtain access using ACTION_OPEN_DOCUMENT or related APIs"))
        }
    }

    @Test
    fun receiptParsingIsOnlyADeclarationAndCannotAssertExternalProof() {
        val parsed = NavigationRandomCorpusOwnedRoot.parseReceipt(bytes(receipt()))
        assertEquals("completion-declarations-only", parsed.evidenceScope)
        assertEquals("prepare", parsed.action)
        assertEquals("failed_closed", parsed.status)
        assertEquals("MalformedControl", parsed.category)
        assertEquals(null, parsed.authorizationId)
        assertEquals(null, parsed.admissionId)
        failure(NavigationRandomCorpusOwnedRoot.Category.ControlTooLarge) {
            NavigationRandomCorpusOwnedRoot.parseReceipt(ByteArray(NavigationRandomCorpusOwnedRoot.MAX_RECEIPT_BYTES + 1))
        }
        for (key in listOf("host_evidence_verified_by_app", "target_persisted_readback_verified")) {
            for (bad in listOf<Any>(true, "false", 0, JSONObject.NULL)) {
                failure(NavigationRandomCorpusOwnedRoot.Category.MalformedControl) {
                    NavigationRandomCorpusOwnedRoot.parseReceipt(bytes(receipt().put(key, bad)))
                }
            }
        }
    }

    @Test
    fun receiptTreeDeclarationsRejectForeignDuplicatesAndUnattemptedCompletion() {
        val tree = DocumentsContract.buildTreeDocumentUri(NavigationRandomCorpusDocumentsProvider.AUTHORITY,
            NavigationRandomCorpusDocumentsProvider.ROOT).toString()
        val invalid = listOf(
            receipt().put("attempted_trees", JSONArray().put("content://foreign/tree/root")),
            receipt().put("attempted_trees", JSONArray().put(tree).put(tree)),
            receipt().put("attempted_trees", JSONArray().put(tree)).put("completed_trees", JSONArray().put(tree).put(tree)),
            receipt().put("completed_trees", JSONArray().put(tree))
        )
        for (value in invalid) failure(NavigationRandomCorpusOwnedRoot.Category.MalformedControl) {
            NavigationRandomCorpusOwnedRoot.parseReceipt(bytes(value))
        }
    }

    /** In-memory syntax fixture only; never written or passed to an admission/activation entry point. */
    private fun receipt(): JSONObject = JSONObject().put("schema_version", 1).put("action", "prepare")
        .put("authorization_id", JSONObject.NULL).put("admission_id", JSONObject.NULL)
        .put("status", "failed_closed").put("category", "MalformedControl")
        .put("manifest_sha256", NavigationRandomCorpusOwnedRoot.FROZEN_MANIFEST_SHA256)
        .put("host_evidence_verified_by_app", false).put("target_persisted_readback_verified", false)
        .put("attempted_trees", JSONArray()).put("completed_trees", JSONArray())
        .put("observed_provider", JSONObject.NULL).put("observed_target", JSONObject.NULL)
        .put("failure_class", JSONObject.NULL).put("device_time_ms", 1L)

    private fun control(): JSONObject = JSONObject().put("schema_version", 1).put("action", "prepare")
        .put("authorization_id", "a".repeat(32)).put("admission_id", "b".repeat(32)).put("run_id", "source-test-1")
        .put("attempt", 1).put("api", 26).put("manifest_sha256", NavigationRandomCorpusOwnedRoot.FROZEN_MANIFEST_SHA256)
        .put("provider_package", NavigationRandomCorpusOwnedRoot.PROVIDER_PACKAGE).put("provider_uid", 10_001)
        .put("provider_first_install_ms", 1L).put("provider_last_update_ms", 2L).put("provider_version_code", 0L)
        .put("provider_apk_sha256", hash(1)).put("provider_signer_sha256", hash(2))
        .put("target_package", NavigationRandomCorpusOwnedRoot.TARGET_PACKAGE).put("target_uid", 10_002)
        .put("target_first_install_ms", 1L).put("target_last_update_ms", 2L).put("target_version_code", 0L)
        .put("target_apk_sha256", hash(3)).put("target_signer_sha256", hash(4))
        .put("host_ownership_receipt_sha256", hash(5)).put("pre_copy_budget_receipt_sha256", hash(6)).put("staged_copy_receipt_sha256", hash(7))

    private fun malformed(value: JSONObject) = failure(NavigationRandomCorpusOwnedRoot.Category.MalformedControl) {
        NavigationRandomCorpusOwnedRoot.parseControl(bytes(value))
    }

    private fun failure(category: NavigationRandomCorpusOwnedRoot.Category, block: () -> Unit) {
        try {
            block()
            fail("Expected admission failure category $category")
        } catch (failure: NavigationRandomCorpusOwnedRoot.Failure) {
            assertEquals(category, failure.category)
        }
    }

    @Suppress("DEPRECATION")
    private fun installedSignerDigest(pm: PackageManager, packageName: String): String {
        val signatures = if (Build.VERSION.SDK_INT >= 28) {
            requireNotNull(pm.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo).apkContentsSigners
        } else {
            pm.getPackageInfo(packageName, PackageManager.GET_SIGNATURES).signatures
        }
        assertEquals(1, requireNotNull(signatures).size)
        return digest(signatures.single().toByteArray())
    }

    private fun fileDigest(file: File): String {
        val hash = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                hash.update(buffer, 0, count)
            }
        }
        return hex(hash.digest())
    }

    private fun bytes(value: JSONObject): ByteArray = value.toString().toByteArray(Charsets.UTF_8)
    private fun hash(value: Int): String = value.toString(16).padStart(64, '0')
    private fun digest(bytes: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(bytes))
    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
}
