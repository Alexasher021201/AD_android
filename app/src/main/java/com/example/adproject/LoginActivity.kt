package com.example.adproject

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.adproject.databinding.ActivityLoginBinding

class LoginActivity : AppCompatActivity() {

    private lateinit var vb: ActivityLoginBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 已登录就跳过登录页
        if (UserSession.isLoggedIn(this)) {
            goExercise()
            finish()
            return
        }

        vb = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(vb.root)

        // 去注册
        vb.linkToRegister.setOnClickListener {
            startActivity(Intent(this, RegisterActivity::class.java))
        }

        // 登录
        vb.btnLogin.setOnClickListener {
            val email = vb.inputEmail.editText?.text?.toString()?.trim().orEmpty()
            val pwd   = vb.inputPassword.editText?.text?.toString()?.trim().orEmpty()

            if (email.isEmpty() || pwd.isEmpty()) {
                Toast.makeText(this, "Please enter email and password", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // 本地临时代码：后端接通后把这里换成 Retrofit 登录接口
            val result = LocalAuthRepository.login(this, email, pwd)
            if (result.isSuccess) {
                val u = result.getOrThrow()
                UserSession.save(this, userId = u.id, userName = u.name, email = u.email)
                Toast.makeText(this, "Login successful", Toast.LENGTH_SHORT).show()
                goExercise()
            } else {
                Toast.makeText(this, result.exceptionOrNull()?.message ?: "Login failed", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun goExercise() {
        startActivity(Intent(this, ExerciseActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        })
    }
}
