TremorWatch Change Proposals With Concrete Code (2026-02-03)

Scope: Proposed code edits for every issue listed in codexreview04022026.md. These are ready-to-apply diffs/snippets. No codebase files were modified.

**Critical Fix 1: Sensor-freeze recovery unregisters the wrong listener**
Patch:
```diff
*** Begin Patch
*** Update File: app/src/main/java/com/opensource/tremorwatch/service/TremorService.kt
@@
-            sensorManager.unregisterListener(this)
+            // Unregister the actual listener to avoid duplicate callbacks
+            sensorManager.unregisterListener(monitoringEngine)
*** End Patch
```

**Critical Fix 2: No end-to-end ACK/idempotency for batch transfers**
Patch (phone sends ACK after durable save):
```diff
*** Begin Patch
*** Update File: phone/src/main/java/com/opensource/tremorwatch/phone/WatchDataListenerService.kt
@@
-    private fun processBatch(batch: TremorBatch) {
+    private fun processBatch(batch: TremorBatch, sourceNodeId: String? = null) {
@@
-            try {
-                saveToLocalStorageImmediate(batch)
+            try {
+                saveToLocalStorageImmediate(batch)
                 Log.i(TAG, "? Saved batch ${batch.batchId} to local storage immediately")
             } catch (e: Exception) {
                 Log.e(TAG, "? CRITICAL: Failed to save batch ${batch.batchId} to local storage: ${e.message}", e)
                 // DO NOT CONTINUE - data will be lost!
                 return
             }
+
+            // Send ACK only after durable local save
+            sourceNodeId?.let { nodeId ->
+                try {
+                    val ack = JSONObject().apply {
+                        put(Constants.KEY_BATCH_ID, batch.batchId)
+                        put(Constants.KEY_TIMESTAMP, System.currentTimeMillis())
+                    }.toString().toByteArray()
+                    Wearable.getMessageClient(this).sendMessage(
+                        nodeId,
+                        Constants.MESSAGE_PATH_BATCH_ACK,
+                        ack
+                    )
+                    Log.i(TAG, "? Sent ACK for batch ${batch.batchId}")
+                } catch (e: Exception) {
+                    Log.w(TAG, "Failed to send ACK for batch ${batch.batchId}: ${e.message}")
+                }
+            }
*** End Patch
```
Wire `sourceNodeId` where batches are received:
```diff
*** Begin Patch
*** Update File: phone/src/main/java/com/opensource/tremorwatch/phone/WatchDataListenerService.kt
@@
-            val batch = TremorBatch.fromJsonString(jsonString)
-            processBatch(batch)
+            val batch = TremorBatch.fromJsonString(jsonString)
+            processBatch(batch, channel.nodeId)
@@
-                    val batch = TremorBatch.fromJsonString(jsonString)
-                    processBatch(batch)
+                    val batch = TremorBatch.fromJsonString(jsonString)
+                    processBatch(batch, messageEvent.sourceNodeId)
@@
-                val batch = TremorBatch.fromJsonString(batchJson)
+                val batch = TremorBatch.fromJsonString(batchJson)
@@
-                saveBatchToQueue(batch)
+                saveBatchToQueue(batch)
+                // Use DataItem host as source node for ACK
+                val sourceNodeId = dataItem.uri.host
+                if (!sourceNodeId.isNullOrBlank()) {
+                    processBatch(batch, sourceNodeId)
+                }
*** End Patch
```
Patch (watch deletes only after ACK; persist pending ACKs):
```diff
*** Begin Patch
*** Update File: app/src/main/java/com/opensource/tremorwatch/service/WatchMessageListenerService.kt
@@
-        when (messageEvent.path) {
+        when (messageEvent.path) {
             Constants.MESSAGE_PATH_LOG_REQUEST -> {
                 handleLogRequest(messageEvent.sourceNodeId)
             }
+            Constants.MESSAGE_PATH_BATCH_ACK -> {
+                handleBatchAck(messageEvent.data)
+            }
             else -> {
                 Log.w(TAG, "Unknown message path: ${messageEvent.path}")
             }
         }
     }
+
+    private fun handleBatchAck(data: ByteArray) {
+        try {
+            val json = JSONObject(String(data, Charsets.UTF_8))
+            val batchId = json.getString(Constants.KEY_BATCH_ID)
+            PendingAckStore(applicationContext).markAcked(batchId)
+            Log.i(TAG, "? ACK received for batch $batchId")
+        } catch (e: Exception) {
+            Log.e(TAG, "Failed to process ACK: ${e.message}")
+        }
+    }
*** End Patch
```
Add a small helper to track pending ACKs (watch):
```kotlin
// app/src/main/java/com/opensource/tremorwatch/util/PendingAckStore.kt
class PendingAckStore(context: Context) {
    private val prefs = context.getSharedPreferences("pending_acks", Context.MODE_PRIVATE)
    fun addPending(batchId: String, filePath: String) { prefs.edit().putString(batchId, filePath).apply() }
    fun markAcked(batchId: String) {
        val path = prefs.getString(batchId, null) ?: return
        File(path).delete()
        prefs.edit().remove(batchId).apply()
    }
}
```
Update watch send path to record pending file on send success (instead of deleting immediately):
```diff
*** Begin Patch
*** Update File: app/src/main/java/com/opensource/tremorwatch/service/TremorService.kt
@@
-                    phoneCommunication.sendBatch(batch) { success ->
+                    phoneCommunication.sendBatch(batch) { success ->
                         if (success) {
-                            // Delete file after successful transmission to phone
-                            if (file.exists()) {
-                                file.delete()
-                                pendingBatchCount--
-                                batchesSent++
-                                lastSuccessfulUploadTime = System.currentTimeMillis()
-                                Timber.i("SUCCESS: Sent batch ${file.name} via queue worker, deleted. $pendingBatchCount pending")
-                            }
+                            // Wait for ACK before deleting local file
+                            PendingAckStore(this).addPending(batch.batchId, file.absolutePath)
+                            batchesSent++
+                            lastSuccessfulUploadTime = System.currentTimeMillis()
+                            Timber.i("SUCCESS: Sent batch ${file.name}; awaiting ACK. $pendingBatchCount pending")
                         } else {
*** End Patch
```

**Critical Fix 3: Non-atomic writes for medical data files**
Patch (watch batch file write using AtomicFile):
```diff
*** Begin Patch
*** Update File: app/src/main/java/com/opensource/tremorwatch/service/TremorService.kt
@@
-            val file = java.io.File(filesDir, filename)
-
-            // Save using shared format
-            file.writeText(sharedBatch.toJsonString())
+            val file = java.io.File(filesDir, filename)
+            val atomicFile = android.util.AtomicFile(file)
+            val fos = atomicFile.startWrite()
+            try {
+                fos.write(sharedBatch.toJsonString().toByteArray())
+                fos.flush()
+                fos.fd.sync()
+                atomicFile.finishWrite(fos)
+            } catch (e: Exception) {
+                atomicFile.failWrite(fos)
+                throw e
+            }
*** End Patch
```
Patch (phone upload_queue and calibration writes using AtomicFile helper):
```kotlin
// phone/src/main/java/com/opensource/tremorwatch/phone/util/AtomicWrites.kt
object AtomicWrites {
    fun writeText(file: File, text: String) {
        val atomic = android.util.AtomicFile(file)
        val fos = atomic.startWrite()
        try {
            fos.write(text.toByteArray())
            fos.flush()
            fos.fd.sync()
            atomic.finishWrite(fos)
        } catch (e: Exception) {
            atomic.failWrite(fos)
            throw e
        }
    }
}
```
Then replace writes:
```diff
*** Begin Patch
*** Update File: phone/src/main/java/com/opensource/tremorwatch/phone/WatchDataListenerService.kt
@@
-            batchFile.writeText(jsonString)
+            AtomicWrites.writeText(batchFile, jsonString)
@@
-            calibrationFile.writeText(calibrationJson)
+            AtomicWrites.writeText(calibrationFile, calibrationJson)
*** End Patch
```

**Critical Fix 4: Duplicate samples possible in Room schema**
Patch (add batchId + sampleIndex, unique constraint):
```diff
*** Begin Patch
*** Update File: phone/src/main/java/com/opensource/tremorwatch/phone/database/TremorSample.kt
@@
 @Entity(
     tableName = "tremor_samples",
     indices = [
-        Index(value = ["timestamp"]),
-        Index(value = ["severity"])
+        Index(value = ["timestamp"]),
+        Index(value = ["severity"]),
+        Index(value = ["batchId", "sampleIndex"], unique = true)
     ]
 )
 data class TremorSample(
     @PrimaryKey(autoGenerate = true)
     val id: Long = 0,
+    val batchId: String,
+    val sampleIndex: Int,
@@
 )
*** End Patch
```
Patch (DAO insert ignores duplicates):
```diff
*** Begin Patch
*** Update File: phone/src/main/java/com/opensource/tremorwatch/phone/database/TremorDao.kt
@@
-    @Insert(onConflict = OnConflictStrategy.REPLACE)
+    @Insert(onConflict = OnConflictStrategy.IGNORE)
     suspend fun insertAll(samples: List<TremorSample>)
*** End Patch
```
Patch (map batchId/sampleIndex when saving):
```diff
*** Begin Patch
*** Update File: phone/src/main/java/com/opensource/tremorwatch/phone/database/TremorDatabaseHelper.kt
@@
-            val samples = batch.samples.map { sample ->
+            val samples = batch.samples.mapIndexed { index, sample ->
                 TremorSample(
+                    batchId = batch.batchId,
+                    sampleIndex = index,
                     timestamp = sample.timestamp,
*** End Patch
```
Patch (Room migration version bump):
```diff
*** Begin Patch
*** Update File: phone/src/main/java/com/opensource/tremorwatch/phone/database/TremorRoomDatabase.kt
@@
-    version = 2,
+    version = 3,
@@
-        private val MIGRATION_1_2 = object : Migration(1, 2) {
+        private val MIGRATION_1_2 = object : Migration(1, 2) {
@@
         }
+
+        private val MIGRATION_2_3 = object : Migration(2, 3) {
+            override fun migrate(database: SupportSQLiteDatabase) {
+                database.execSQL("ALTER TABLE tremor_samples ADD COLUMN batchId TEXT NOT NULL DEFAULT ''")
+                database.execSQL("ALTER TABLE tremor_samples ADD COLUMN sampleIndex INTEGER NOT NULL DEFAULT 0")
+                database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_tremor_samples_batchId_sampleIndex ON tremor_samples(batchId, sampleIndex)")
+            }
+        }
@@
-                .addMigrations(MIGRATION_1_2)
+                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
*** End Patch
```

**Performance Improvement 1: Data Layer concurrency / unbounded queue**
Patch (bounded queue + trySend fallback):
```diff
*** Begin Patch
*** Update File: shared/src/main/java/com/opensource/tremorwatch/shared/Constants.kt
@@
+    const val SEND_QUEUE_CAPACITY = 20
*** End Patch
```
```diff
*** Begin Patch
*** Update File: app/src/main/java/WatchDataSender.kt
@@
-        private val sendQueue = Channel<QueuedBatch>(capacity = Channel.UNLIMITED)
+        private val sendQueue = Channel<QueuedBatch>(capacity = Constants.SEND_QUEUE_CAPACITY)
@@
-                Companion.sendQueue.send(Companion.QueuedBatch(batch, onComplete, context))
+                val result = Companion.sendQueue.trySend(Companion.QueuedBatch(batch, onComplete, context))
+                if (result.isFailure) {
+                    // Queue full, persist to disk and return
+                    queueBatchForLater(batch)
+                    onComplete(false)
+                    return@launch
+                }
*** End Patch
```

**Performance Improvement 2: Accelerometer registered at ~10 Hz but FFT assumes 50 Hz**
Patch (align accel rate to gyro for FFT):
```diff
*** Begin Patch
*** Update File: app/src/main/java/com/opensource/tremorwatch/service/TremorService.kt
@@
-                    val result = sensorManager.registerListener(monitoringEngine, it, SensorManager.SENSOR_DELAY_NORMAL)
+                    val result = sensorManager.registerListener(monitoringEngine, it, SensorManager.SENSOR_DELAY_GAME)
*** End Patch
```

**Performance Improvement 3: Channel length prefix not validated**
Patch (add max size and reject):
```diff
*** Begin Patch
*** Update File: phone/src/main/java/com/opensource/tremorwatch/phone/WatchDataListenerService.kt
@@
     private suspend fun handleChannelBatch(channel: ChannelClient.Channel) {
         try {
@@
-            Log.d(TAG, "Reading $dataLength bytes of compressed data from channel")
+            val maxBytes = 5 * 1024 * 1024  // 5 MB cap
+            if (dataLength <= 0 || dataLength > maxBytes) {
+                Log.e(TAG, "Invalid channel length: $dataLength")
+                inputStream.close()
+                channelClient.close(channel).await()
+                return
+            }
+            Log.d(TAG, "Reading $dataLength bytes of compressed data from channel")
*** End Patch
```

**Performance Improvement 4: Stale chunk assemblies never cleaned**
Patch (cleanup on each chunk):
```diff
*** Begin Patch
*** Update File: phone/src/main/java/com/opensource/tremorwatch/phone/WatchDataListenerService.kt
@@
     private fun handleTremorChunk(messageEvent: MessageEvent) {
         try {
+            // Cleanup stale assemblies opportunistically
+            startChunkCleanup()
*** End Patch
```

**Performance Improvement 5: Synchronous DataStore getters can block UI**
Patch (deprecate sync helpers to force migration):
```diff
*** Begin Patch
*** Update File: app/src/main/java/com/opensource/tremorwatch/data/PreferencesRepository.kt
@@
-    fun getIsMonitoring(): Boolean = runBlocking { isMonitoring.first() }
+    @Deprecated("Use Flow/suspend APIs; this can block UI")
+    fun getIsMonitoring(): Boolean = runBlocking { isMonitoring.first() }
*** End Patch
```
Then migrate call sites to Flow in ViewModels:
```kotlin
// Example usage in a ViewModel
val isMonitoring: StateFlow<Boolean> = prefsRepo.isMonitoring.stateIn(viewModelScope, SharingStarted.Eagerly, false)
```

**Suggested Feature 1: Wire ViewModels into UI + preserve state**
Patch (watch MainActivity Compose):
```kotlin
val vm: MainViewModel = viewModel()
val pending by vm.pendingBatchCount.collectAsStateWithLifecycle()
// use pending in UI
```
Patch (phone MainActivity Compose):
```kotlin
val vm: MainViewModel = viewModel()
val pending by vm.pendingBatchCount.collectAsStateWithLifecycle()
var influxDatabase by rememberSaveable { mutableStateOf(vm.getInfluxDbDatabase()) }
```

**Suggested Feature 2: Wear OS ambient mode handling**
Patch (watch MainActivity):
```kotlin
class MainActivity : ComponentActivity(), AmbientModeSupport.AmbientCallbackProvider {
    private lateinit var ambientController: AmbientModeSupport.AmbientController
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ambientController = AmbientModeSupport.attach(this)
    }
    override fun getAmbientCallback(): AmbientModeSupport.AmbientCallback = object : AmbientModeSupport.AmbientCallback() {
        override fun onEnterAmbient(ambientDetails: Bundle?) { /* reduce UI updates */ }
        override fun onExitAmbient() { /* restore UI */ }
    }
}
```

**Suggested Feature 3: Batch integrity checks + sync health metrics**
Patch (watch compute checksum):
```kotlin
val checksum = MessageDigest.getInstance("SHA-256")
    .digest(batch.toJsonString().toByteArray())
    .joinToString("") { "%02x".format(it) }
```
Include `checksum` in the transfer JSON and verify on phone before ACK.

**Suggested Feature 4: At-rest encryption for medical datasets**
Patch (Room SQLCipher example):
```kotlin
val passphrase = SQLiteDatabase.getBytes("your-key".toCharArray())
val factory = SupportFactory(passphrase)
Room.databaseBuilder(context, TremorRoomDatabase::class.java, "tremor_data.db")
    .openHelperFactory(factory)
    .build()
```
Patch (EncryptedFile for queue JSON):
```kotlin
val file = File(context.filesDir, "upload_queue/batch_123.json")
val encryptedFile = EncryptedFile.Builder(
    context,
    file,
    MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
    EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB
).build()
```

---

## Claude Code Review (2026-02-04)

### Overall Assessment
These are well-designed patches that address real issues. The code is production-quality and follows Android best practices.

### Implementation Priority (Recommended Order)

**Immediate (High Impact, Low Risk):**
1. **Critical Fix 1** - Sensor listener bug. One-line fix, no side effects.
2. **Performance Fix 3** - Channel length validation. Simple defensive check.
3. **Critical Fix 3** - Atomic writes. Prevents data corruption on crash.

**Next Sprint (Medium Complexity):**
4. **Critical Fix 4** - Duplicate prevention. Requires DB migration v2→v3, so do before more data accumulates.
5. **Performance Fix 2** - Accelerometer rate alignment. Simple change but increases battery slightly.
6. **Performance Fix 4** - Chunk cleanup on receive. One-line addition.

**Larger Effort (High Complexity):**
7. **Critical Fix 2** - ACK system. Most complex change spanning watch + phone. Needs:
   - New `PendingAckStore` class
   - Message path constant `MESSAGE_PATH_BATCH_ACK`
   - Changes to both `WatchDataSender`, `TremorService`, `WatchMessageListenerService`, and `WatchDataListenerService`
   - Consider: What happens if ACK never arrives? Need timeout/retry logic or stale pending cleanup.

**Defer (Nice to Have):**
8. Performance Fix 1 - Bounded queue (needs `queueBatchForLater()` implementation)
9. Performance Fix 5 - DataStore sync deprecation (requires call-site migration)
10. Suggested Features 1-4 - ViewModel wiring, ambient mode, checksums, encryption

### Notes on Fix 2 (ACK System)
The proposed implementation is solid but consider adding:
- **Stale pending cleanup**: If ACK not received within X hours (e.g., 24h), either retry send or delete pending entry to prevent unbounded growth
- **Retry on reconnect**: When phone reconnects, resend any batches still pending ACK
- **Metrics**: Track ACK success rate for debugging sync issues

### Testing Recommendations
- Fix 1: Simulate sensor freeze by pausing in debugger, verify no duplicate callbacks
- Fix 3: Kill app mid-write, verify file not corrupted on restart
- Fix 4: Send same batch twice, verify only one copy in DB
- Fix 2: Kill phone app after receive but before ACK, verify watch retains batch
