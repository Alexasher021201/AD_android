package com.example.adproject

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 一个极简“本地用户库”，用 SharedPreferences 保存用户列表。
 * 仅供后端未接通前使用；接通后把这里的调用换成真正的 API 即可。
 */
object LocalAuthRepository {
    private const val PREF = "local_auth_pref"
    private const val KEY_USERS = "users"        // 存 JSONArray，每个元素：{id, name, email, password}

    // 读用户列表
    private fun readUsers(ctx: Context): JSONArray {
        val s = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getString(KEY_USERS, "[]") ?: "[]"
        return JSONArray(s)
    }

    // 写用户列表
    private fun writeUsers(ctx: Context, arr: JSONArray) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putString(KEY_USERS, arr.toString()).apply()
    }

    fun register(ctx: Context, name: String, email: String, password: String): Result<UserInfo> {
        val users = readUsers(ctx)
        // 已存在同邮箱？
        for (i in 0 until users.length()) {
            if (users.getJSONObject(i).getString("email").equals(email, ignoreCase = true)) {
                return Result.failure(IllegalStateException("Email already registered"))
            }
        }
        val id = (System.currentTimeMillis() / 1000).toInt()  // 简单生成个本地 id
        val obj = JSONObject().apply {
            put("id", id)
            put("name", name)
            put("email", email)
            put("password", password) // 仅本地测试，**不要**在真实环境明文保存
        }
        users.put(obj)
        writeUsers(ctx, users)
        return Result.success(UserInfo(id, name, email))
    }

    fun login(ctx: Context, email: String, password: String): Result<UserInfo> {
        val users = readUsers(ctx)
        for (i in 0 until users.length()) {
            val u = users.getJSONObject(i)
            if (u.getString("email").equals(email, ignoreCase = true)
                && u.getString("password") == password) {
                return Result.success(UserInfo(u.getInt("id"), u.getString("name"), u.getString("email")))
            }
        }
        return Result.failure(IllegalArgumentException("Incorrect email or password"))
    }

    data class UserInfo(val id: Int, val name: String, val email: String)
}
