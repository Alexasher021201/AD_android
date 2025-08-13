package com.example.adproject

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.adproject.databinding.ActivityRegisterBinding

class RegisterActivity : AppCompatActivity() {

    private lateinit var vb: ActivityRegisterBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        vb = ActivityRegisterBinding.inflate(layoutInflater)
        setContentView(vb.root)

        // 已有账号？去登录
        vb.linkToLogin.setOnClickListener { finish() }

        vb.btnRegister.setOnClickListener {
            val name = vb.inputName.editText?.text?.toString()?.trim().orEmpty()
            val email = vb.inputEmail.editText?.text?.toString()?.trim().orEmpty()
            val pwd = vb.inputPassword.editText?.text?.toString()?.trim().orEmpty()

            if (name.isEmpty() || email.isEmpty() || pwd.isEmpty()) {
                Toast.makeText(this, "Please complete all fields", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            // 简单邮箱校验（可按需删除/替换）
            if (!android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
                Toast.makeText(this, "Invalid email format", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // 本地临时注册；后端接通后替换为接口调用
            vb.btnRegister.isEnabled = false
            val result = LocalAuthRepository.register(this, name, email, pwd)
            vb.btnRegister.isEnabled = true

            if (result.isSuccess) {
                val u = result.getOrThrow()
                UserSession.save(this, userId = u.id, userName = u.name, email = u.email)
                Toast.makeText(this, "Registration successful", Toast.LENGTH_SHORT).show()
                startActivity(Intent(this, ExerciseActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                })
            } else {
                Toast.makeText(this, result.exceptionOrNull()?.message ?: "Registration failed", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
