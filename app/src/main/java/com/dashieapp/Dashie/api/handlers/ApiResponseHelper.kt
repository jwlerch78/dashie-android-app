package com.dashieapp.Dashie.api.handlers

import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject

/**
 * Shared response helpers for API handler classes.
 */

internal fun successResponse(message: String): NanoHTTPD.Response {
    val json = JSONObject().apply {
        put("status", "OK")
        put("message", message)
    }
    return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "application/json", json.toString())
}

internal fun errorResponse(message: String, status: NanoHTTPD.Response.Status = NanoHTTPD.Response.Status.BAD_REQUEST): NanoHTTPD.Response {
    val json = JSONObject().apply {
        put("status", "ERROR")
        put("message", message)
    }
    return NanoHTTPD.newFixedLengthResponse(status, "application/json", json.toString())
}
