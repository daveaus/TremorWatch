package com.opensource.tremorwatch.shared

/**
 * Shared constants between watch and phone apps
 */
object Constants {
    // Data Layer paths (legacy - migrating to MessageClient)
    const val PATH_TREMOR_BATCH = "/tremor_batch"
    const val PATH_STATUS_REQUEST = "/status_request"
    const val PATH_STATUS_RESPONSE = "/status_response"
    const val PATH_SYNC_REQUEST = "/sync_request"
    const val PATH_HEARTBEAT = "/heartbeat"

    // MessageClient paths (preferred for streaming data)
    const val MESSAGE_PATH_TREMOR_CHUNK = "/tremor_chunk"
    const val MESSAGE_PATH_BATCH_ACK = "/batch_ack"
    const val MESSAGE_PATH_DIAGNOSTIC_EVENT = "/diagnostic_event"
    const val MESSAGE_PATH_LOG_REQUEST = "/log_request"
    const val MESSAGE_PATH_LOG_RESPONSE = "/log_response"

    // Capability names for device discovery
    const val CAPABILITY_TREMOR_RECEIVER = "tremor_watch_receiver"

    // Message keys
    const val KEY_BATCH_DATA = "batch_data"
    const val KEY_BATCH_ID = "batch_id"
    const val KEY_TIMESTAMP = "timestamp"
    const val KEY_SAMPLE_COUNT = "sample_count"
    const val KEY_PENDING_COUNT = "pending_count"
    const val KEY_LAST_UPLOAD = "last_upload"
    const val KEY_UPLOAD_SUCCESS = "upload_success"
    const val KEY_SERVICE_UPTIME = "service_uptime"
    const val KEY_MONITORING_STATE = "monitoring_state"

    // Chunking keys
    const val KEY_CHUNK_INDEX = "chunk_index"
    const val KEY_TOTAL_CHUNKS = "total_chunks"
    const val KEY_CHUNK_DATA = "chunk_data"

    // Shared preferences keys
    const val PREF_LAST_SYNC_TIME = "last_sync_time"
    const val PREF_TOTAL_BATCHES_SENT = "total_batches_sent"
    const val PREF_TOTAL_BATCHES_RECEIVED = "total_batches_received"

    // Timeouts and intervals
    const val DATA_LAYER_TIMEOUT_MS = 10000L
    const val MESSAGE_TIMEOUT_MS = 30000L  // MessageClient timeout - increased to 30s for congested Data Layer
    const val BATCH_INTERVAL_MS = 60000L  // 1 minute
    const val STATUS_UPDATE_INTERVAL_MS = 30000L  // 30 seconds
    const val HEARTBEAT_INTERVAL_MS = 900000L  // 15 minutes
    const val PENDING_QUEUE_RETRY_INTERVAL_MS = 300000L  // 5 minutes - retry failed batches

    // Batch configuration
    const val MAX_SAMPLES_PER_BATCH = 100
    const val MAX_BATCH_AGE_MS = 300000L  // 5 minutes

    // Chunking configuration (for MessageClient)
    const val MAX_CHUNK_SIZE_BYTES = 8000  // Increased to 8KB - Wear OS MessageClient handles up to 100KB
    const val CHUNK_SEND_DELAY_MS = 50L  // Minimal delay - data layer handles buffering efficiently

    // Parallel worker configuration
    const val PARALLEL_SEND_WORKERS = 3  // Number of concurrent batch senders
    const val NODE_CACHE_DURATION_MS = 30000L  // Cache connected nodes for 30 seconds

    // Retry configuration
    const val MAX_SEND_RETRIES = 3
    const val INITIAL_RETRY_DELAY_MS = 1000L
    const val MAX_RETRY_DELAY_MS = 30000L  // 30 seconds max backoff
    const val UPLOAD_MAX_RETRIES = 3  // Phone-side upload retries
    const val UPLOAD_INITIAL_RETRY_DELAY_MS = 2000L  // Longer initial delay for network errors
}
