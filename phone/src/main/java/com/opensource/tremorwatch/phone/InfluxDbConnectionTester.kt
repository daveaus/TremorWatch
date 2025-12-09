package com.opensource.tremorwatch.phone

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * Utility class for testing InfluxDB connections and managing databases.
 */
object InfluxDbConnectionTester {
    private const val TAG = "InfluxDbTester"
    
    /**
     * Result of a connection test
     */
    data class TestResult(
        val success: Boolean,
        val message: String,
        val databaseExists: Boolean = false,
        val databaseCreated: Boolean = false
    )
    
    /**
     * Test InfluxDB connection and ensure database exists.
     * Creates the database if it doesn't exist.
     * 
     * @param url InfluxDB URL (e.g., "http://10.0.0.10:8086")
     * @param database Database name
     * @param username Optional username (empty string if no auth)
     * @param password Optional password (empty string if no auth)
     * @return TestResult with success status and message
     */
    suspend fun testConnectionAndCreateDatabase(
        url: String,
        database: String,
        username: String = "",
        password: String = ""
    ): TestResult = withContext(Dispatchers.IO) {
        try {
            // Validate inputs
            if (url.isBlank()) {
                return@withContext TestResult(false, "URL cannot be empty")
            }
            if (database.isBlank()) {
                return@withContext TestResult(false, "Database name cannot be empty")
            }
            
            // Parse URL
            val baseUrl = url.trimEnd('/')
            val parsedUrl = try {
                URL(baseUrl)
            } catch (e: Exception) {
                return@withContext TestResult(false, "Invalid URL format: ${e.message}")
            }
            
            // Test ping endpoint first
            val pingResult = testPing(baseUrl)
            if (!pingResult.success) {
                return@withContext pingResult
            }
            
            // Check if database exists
            val dbExists = checkDatabaseExists(baseUrl, database, username, password)
            
            if (dbExists) {
                return@withContext TestResult(
                    success = true,
                    message = "Connection successful. Database '$database' exists.",
                    databaseExists = true
                )
            }
            
            // Database doesn't exist, create it
            val createResult = createDatabase(baseUrl, database, username, password)
            if (createResult.success) {
                return@withContext TestResult(
                    success = true,
                    message = "Connection successful. Database '$database' created.",
                    databaseExists = false,
                    databaseCreated = true
                )
            } else {
                return@withContext createResult
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error testing InfluxDB connection", e)
            return@withContext TestResult(false, "Error: ${e.message ?: "Unknown error"}")
        }
    }
    
    /**
     * Test InfluxDB ping endpoint
     */
    private suspend fun testPing(baseUrl: String): TestResult {
        return try {
            val pingUrl = URL("$baseUrl/ping")
            val connection = pingUrl.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            
            val responseCode = connection.responseCode
            connection.disconnect()
            
            if (responseCode == 204 || responseCode == 200) {
                TestResult(true, "Ping successful")
            } else {
                TestResult(false, "Ping failed with code: $responseCode")
            }
        } catch (e: Exception) {
            TestResult(false, "Cannot connect to InfluxDB: ${e.message}")
        }
    }
    
    /**
     * Check if database exists by querying SHOW DATABASES
     */
    private suspend fun checkDatabaseExists(
        baseUrl: String,
        database: String,
        username: String,
        password: String
    ): Boolean {
        return try {
            val queryUrl = URL("$baseUrl/query?q=SHOW+DATABASES")
            val connection = queryUrl.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            
            // Add authentication if provided
            if (username.isNotEmpty() && password.isNotEmpty()) {
                val auth = android.util.Base64.encode(
                    "$username:$password".toByteArray(),
                    android.util.Base64.NO_WRAP
                )
                connection.setRequestProperty("Authorization", "Basic ${String(auth)}")
            }
            
            val responseCode = connection.responseCode
            if (responseCode == 200) {
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                connection.disconnect()
                
                // Parse response to check if database exists
                // InfluxDB v1.x returns JSON like: {"results":[{"series":[{"name":"databases","columns":["name"],"values":[["_internal"],["database1"],["database2"]]}]}]}
                response.contains("\"$database\"")
            } else {
                connection.disconnect()
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking database existence", e)
            false
        }
    }
    
    /**
     * Create database using CREATE DATABASE query
     */
    private suspend fun createDatabase(
        baseUrl: String,
        database: String,
        username: String,
        password: String
    ): TestResult {
        return try {
            // URL encode the query (database name is already in quotes, so URL encode the whole query)
            val query = "CREATE DATABASE \"$database\""
            val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
            val queryUrl = URL("$baseUrl/query?q=$encodedQuery")
            
            val connection = queryUrl.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            
            // Add authentication if provided
            if (username.isNotEmpty() && password.isNotEmpty()) {
                val auth = android.util.Base64.encode(
                    "$username:$password".toByteArray(),
                    android.util.Base64.NO_WRAP
                )
                connection.setRequestProperty("Authorization", "Basic ${String(auth)}")
            }
            
            val responseCode = connection.responseCode
            val response = if (responseCode == 200) {
                connection.inputStream.bufferedReader().use { it.readText() }
            } else {
                connection.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
            }
            connection.disconnect()
            
            if (responseCode == 200) {
                TestResult(true, "Database created successfully")
            } else {
                // Check if error is because database already exists
                if (response.contains("already exists", ignoreCase = true)) {
                    TestResult(true, "Database already exists")
                } else {
                    TestResult(false, "Failed to create database: HTTP $responseCode - $response")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error creating database", e)
            TestResult(false, "Error creating database: ${e.message}")
        }
    }
}

