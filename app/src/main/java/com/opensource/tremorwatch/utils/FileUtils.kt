package com.opensource.tremorwatch.utils

import timber.log.Timber
import java.io.File
import java.io.IOException

/**
 * Common file I/O utilities to reduce code duplication.
 * 
 * Provides safe file operations with consistent error handling.
 */
object FileUtils {
    
    /**
     * Safely read text from a file.
     * 
     * @return The file contents, or null if read failed
     */
    fun readTextSafely(file: File): String? {
        return try {
            if (!file.exists()) {
                ErrorHandler.logFileError("read", file.absolutePath, null, mapOf("reason" to "file_not_found"))
                return null
            }
            
            file.readText()
        } catch (e: IOException) {
            ErrorHandler.logFileError("read", file.absolutePath, e)
            null
        } catch (e: SecurityException) {
            ErrorHandler.logFileError("read", file.absolutePath, e, mapOf("reason" to "permission_denied"))
            null
        }
    }
    
    /**
     * Safely write text to a file.
     * 
     * @return true if write succeeded, false otherwise
     */
    fun writeTextSafely(file: File, text: String): Boolean {
        return try {
            // Ensure parent directory exists
            file.parentFile?.mkdirs()
            file.writeText(text)
            true
        } catch (e: IOException) {
            ErrorHandler.logFileError("write", file.absolutePath, e)
            false
        } catch (e: SecurityException) {
            ErrorHandler.logFileError("write", file.absolutePath, e, mapOf("reason" to "permission_denied"))
            false
        }
    }
    
    /**
     * Safely append text to a file.
     * 
     * @return true if append succeeded, false otherwise
     */
    fun appendTextSafely(file: File, text: String): Boolean {
        return try {
            // Ensure parent directory exists
            file.parentFile?.mkdirs()
            file.appendText(text)
            true
        } catch (e: IOException) {
            ErrorHandler.logFileError("append", file.absolutePath, e)
            false
        } catch (e: SecurityException) {
            ErrorHandler.logFileError("append", file.absolutePath, e, mapOf("reason" to "permission_denied"))
            false
        }
    }
    
    /**
     * Safely delete a file.
     * 
     * @return true if delete succeeded, false otherwise
     */
    fun deleteSafely(file: File): Boolean {
        return try {
            if (!file.exists()) {
                Timber.d("File does not exist, skipping delete: ${file.absolutePath}")
                return true // Not an error if file doesn't exist
            }
            
            val deleted = file.delete()
            if (!deleted) {
                ErrorHandler.logFileError("delete", file.absolutePath, null, mapOf("reason" to "delete_failed"))
            }
            deleted
        } catch (e: SecurityException) {
            ErrorHandler.logFileError("delete", file.absolutePath, e, mapOf("reason" to "permission_denied"))
            false
        }
    }
    
    /**
     * Get file size in a human-readable format.
     */
    fun getFileSizeHumanReadable(file: File): String {
        if (!file.exists()) return "0 B"
        
        val bytes = file.length()
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            bytes < 1024 * 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
            else -> "${bytes / (1024 * 1024 * 1024)} GB"
        }
    }
    
    /**
     * List files matching a filter pattern.
     * 
     * @param directory The directory to search
     * @param prefix Optional filename prefix filter
     * @param suffix Optional filename suffix filter
     * @return List of matching files, sorted by name
     */
    fun listFiles(
        directory: File,
        prefix: String? = null,
        suffix: String? = null
    ): List<File> {
        if (!directory.exists() || !directory.isDirectory) {
            return emptyList()
        }
        
        return directory.listFiles { file ->
            val name = file.name
            (prefix == null || name.startsWith(prefix)) &&
            (suffix == null || name.endsWith(suffix))
        }?.sortedBy { it.name } ?: emptyList()
    }
}

