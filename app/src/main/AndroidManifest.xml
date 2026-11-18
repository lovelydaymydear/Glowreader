package com.glowreader.app

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.io.BufferedReader
import java.io.InputStreamReader

class MainActivity : AppCompatActivity() {

    private lateinit var txtContent: EditText
    private lateinit var btnAbrirArquivo: Button

    private val PICK_FILE_REQUEST_CODE = 1001

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        txtContent = findViewById(R.id.txtContent)
        btnAbrirArquivo = findViewById(R.id.btnAbrirArquivo)

        btnAbrirArquivo.setOnClickListener {
            lancarSeletorDeArquivos()
        }
    }

    private fun lancarSeletorDeArquivos() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf(
                "text/plain",
                "application/pdf",
                "application/epub+zip"
            ))
        }
        startActivityForResult(intent, PICK_FILE_REQUEST_CODE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == PICK_FILE_REQUEST_CODE && resultCode == Activity.RESULT_OK) {
            data?.data?.let { uri ->
                processarArquivoEscolhido(uri)
            }
        }
    }

    private fun processarArquivoEscolhido(uri: Uri) {
        val mimeType = contentResolver.getType(uri)

        try {
            when {
                mimeType == "text/plain" || uri.toString().endsWith(".txt", ignoreCase = true) -> {
                    val texto = lerTextoSimples(uri)
                    txtContent.setText(texto)
                    Toast.makeText(this, "Arquivo .txt carregado com sucesso!", Toast.LENGTH_SHORT).show()
                }
                mimeType == "application/pdf" || uri.toString().endsWith(".pdf", ignoreCase = true) -> {
                    Toast.makeText(this, "Arquivo PDF selecionado. Extração em breve!", Toast.LENGTH_LONG).show()
                }
                uri.toString().endsWith(".epub", ignoreCase = true) -> {
                    Toast.makeText(this, "Arquivo EPUB selecionado. Extração em breve!", Toast.LENGTH_LONG).show()
                }
                else -> {
                    Toast.makeText(this, "Formato de arquivo não suportado.", Toast.LENGTH_SHORT).show()
                }
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Erro ao ler o arquivo: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun lerTextoSimples(uri: Uri): String {
        val inputStream = contentResolver.openInputStream(uri)
        val reader = BufferedReader(InputStreamReader(inputStream))
        val stringBuilder = StringBuilder()
        var line: String? = reader.readLine()
        while (line != null) {
            stringBuilder.append(line).append("\n")
            line = reader.readLine()
        }
        inputStream?.close()
        return stringBuilder.toString()
    }
}
