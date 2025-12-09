package com.opensource.tremorwatch.utils

import timber.log.Timber

/**
 * Error handling utilities for consistent error reporting and context.
 * 
 * Provides structured error messages with operation context and error codes
 * for better debugging and programmatic error handling.
 */
object ErrorHandler {
    
    /**
     * Error codes for different operation types.
     */
    enum class ErrorCode(val code: Int, val category: String) {
        // File I/O errors (1000-1999)
        FILE_READ_ERROR(1001, "File I/O"),
        FILE_WRITE_ERROR(1002, "File I/O"),
        FILE_DELETE_ERROR(1003, "File I/O"),
        FILE_NOT_FOUND(1004, "File I/O"),
        
        // Network errors (2000-2999)
        NETWORK_UNAVAILABLE(2001, "Network"),
        NETWORK_TIMEOUT(2002, "Network"),
        NETWORK_CONNECTION_FAILED(2003, "Network"),
        
        // Sensor errors (3000-3999)
        SENSOR_NOT_AVAILABLE(3001, "Sensor"),
        SENSOR_REGISTRATION_FAILED(3002, "Sensor"),
        SENSOR_DATA_INVALID(3003, "Sensor"),
        
        // Data processing errors (4000-4999)
        JSON_PARSE_ERROR(4001, "Data Processing"),
        JSON_SERIALIZE_ERROR(4002, "Data Processing"),
        BATCH_PROCESSING_ERROR(4003, "Data Processing"),
        
        // Service errors (5000-5999)
        SERVICE_START_FAILED(5001, "Service"),
        SERVICE_STOP_FAILED(5002, "Service"),
        WAKELOCK_ACQUIRE_FAILED(5003, "Service"),
        
        // Unknown error
        UNKNOWN_ERROR(9999, "Unknown")
    }
    
    /**
     * Structured error information.
     */
    data class ErrorInfo(
        val code: ErrorCode,
        val operation: String,
        val message: String,
        val throwable: Throwable? = null,
        val context: Map<String, String>? = null
    ) {
        /**
         * Get user-friendly error message.
         */
        fun getUserMessage(): String {
            return when (code) {
                ErrorCode.FILE_READ_ERROR -> "Failed to read file: $operation"
                ErrorCode.FILE_WRITE_ERROR -> "Failed to save data: $operation"
                ErrorCode.NETWORK_UNAVAILABLE -> "Network unavailable. Please check your connection."
                ErrorCode.NETWORK_TIMEOUT -> "Network request timed out. Please try again."
                ErrorCode.SENSOR_NOT_AVAILABLE -> "Required sensor not available on this device."
                ErrorCode.SENSOR_REGISTRATION_FAILED -> "Failed to start sensor monitoring."
                ErrorCode.SERVICE_START_FAILED -> "Failed to start monitoring service."
                else -> "An error occurred: $operation"
            }
        }
        
        /**
         * Get technical error message for logging.
         */
        fun getTechnicalMessage(): String {
            val contextStr = context?.entries?.joinToString(", ") { "${it.key}=${it.value}" } ?: ""
            val throwableStr = throwable?.let { " - ${it.javaClass.simpleName}: ${it.message}" } ?: ""
            return "[${code.category}] ${code.code}: $operation - $message$throwableStr${if (contextStr.isNotEmpty()) " ($contextStr)" else ""}"
        }
    }
    
    /**
     * Log an error with full context.
     */
    fun logError(errorInfo: ErrorInfo) {
        val technicalMsg = errorInfo.getTechnicalMessage()
        errorInfo.throwable?.let {
            Timber.e(it, technicalMsg)
        } ?: Timber.e(technicalMsg)
    }
    
    /**
     * Create and log an error for file operations.
     */
    fun logFileError(
        operation: String,
        filePath: String,
        throwable: Throwable? = null,
        additionalContext: Map<String, String>? = null
    ): ErrorInfo {
        val code = when (operation.lowercase()) {
            "read" -> ErrorCode.FILE_READ_ERROR
            "write", "save" -> ErrorCode.FILE_WRITE_ERROR
            "delete" -> ErrorCode.FILE_DELETE_ERROR
            else -> ErrorCode.FILE_NOT_FOUND
        }
        
        val context = mutableMapOf<String, String>("file" to filePath)
        additionalContext?.let { 
            it.forEach { (k, v) -> context[k] = v.toString() }
        }
        
        val errorInfo = ErrorInfo(
            code = code,
            operation = operation,
            message = "File operation failed: $filePath",
            throwable = throwable,
            context = context
        )
        
        logError(errorInfo)
        return errorInfo
    }
    
    /**
     * Create and log an error for network operations.
     */
    fun logNetworkError(
        operation: String,
        url: String? = null,
        throwable: Throwable? = null,
        additionalContext: Map<String, String>? = null
    ): ErrorInfo {
        val code = when {
            throwable is java.net.SocketTimeoutException -> ErrorCode.NETWORK_TIMEOUT
            throwable is java.net.UnknownHostException -> ErrorCode.NETWORK_CONNECTION_FAILED
            else -> ErrorCode.NETWORK_UNAVAILABLE
        }
        
        val context = mutableMapOf<String, String>()
        url?.let { context["url"] = it }
        additionalContext?.let { 
            it.forEach { (k, v) -> context[k] = v.toString() }
        }
        
        val errorInfo = ErrorInfo(
            code = code,
            operation = operation,
            message = "Network operation failed${url?.let { ": $it" } ?: ""}",
            throwable = throwable,
            context = if (context.isNotEmpty()) context else null
        )
        
        logError(errorInfo)
        return errorInfo
    }
    
    /**
     * Create and log an error for sensor operations.
     */
    fun logSensorError(
        operation: String,
        sensorType: String,
        throwable: Throwable? = null,
        additionalContext: Map<String, String>? = null
    ): ErrorInfo {
        val code = when (operation.lowercase()) {
            "register" -> ErrorCode.SENSOR_REGISTRATION_FAILED
            "available" -> ErrorCode.SENSOR_NOT_AVAILABLE
            else -> ErrorCode.SENSOR_DATA_INVALID
        }
        
        val context = mutableMapOf<String, String>("sensor" to sensorType)
        additionalContext?.let { 
            it.forEach { (k, v) -> context[k] = v.toString() }
        }
        
        val errorInfo = ErrorInfo(
            code = code,
            operation = operation,
            message = "Sensor operation failed: $sensorType",
            throwable = throwable,
            context = context
        )
        
        logError(errorInfo)
        return errorInfo
    }
    
    /**
     * Create and log an error for data processing operations.
     */
    fun logDataProcessingError(
        operation: String,
        dataType: String,
        throwable: Throwable? = null,
        additionalContext: Map<String, String>? = null
    ): ErrorInfo {
        val code = when {
            throwable?.message?.contains("JSON", ignoreCase = true) == true -> ErrorCode.JSON_PARSE_ERROR
            throwable?.message?.contains("parse", ignoreCase = true) == true -> ErrorCode.JSON_PARSE_ERROR
            else -> ErrorCode.BATCH_PROCESSING_ERROR
        }
        
        val context = mutableMapOf<String, String>("dataType" to dataType)
        additionalContext?.let { 
            it.forEach { (k, v) -> context[k] = v.toString() }
        }
        
        val errorInfo = ErrorInfo(
            code = code,
            operation = operation,
            message = "Data processing failed: $dataType",
            throwable = throwable,
            context = context
        )
        
        logError(errorInfo)
        return errorInfo
    }
}

