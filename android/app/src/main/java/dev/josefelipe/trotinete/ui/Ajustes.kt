package dev.josefelipe.trotinete.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import dev.josefelipe.trotinete.dados.Configuracao
import dev.josefelipe.trotinete.dados.Definicoes
import dev.josefelipe.trotinete.dados.Passo
import dev.josefelipe.trotinete.dados.QrTrotinete
import dev.josefelipe.trotinete.dados.formatarMac
import dev.josefelipe.trotinete.dados.lerQrTrotinete

private val FORMATO_CHAVE = Regex("[0-9a-f]{64}")

private fun lerQr(context: Context, lido: (String) -> Unit, falhou: (String) -> Unit) {
    val opcoes = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
    GmsBarcodeScanning.getClient(context, opcoes).startScan()
        .addOnSuccessListener { b -> b.rawValue?.let(lido) }
        .addOnFailureListener { falhou("Não consegui abrir o leitor de QR: ${it.message}") }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Ajustes(defs: Definicoes, voltar: (() -> Unit)?, guardado: () -> Unit) {
    val context = LocalContext.current
    val passo by Configuracao.passo.collectAsStateWithLifecycle()
    var qr by remember { mutableStateOf(defs.mac?.let { QrTrotinete(it, null) }) }
    var avisoQr by remember { mutableStateOf<String?>(null) }
    var avancado by remember { mutableStateOf(false) }

    LaunchedEffect(passo) {
        when (val p = passo) {
            is Passo.AbrirLogin -> context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(p.url)))
            Passo.Concluido -> {
                Configuracao.reiniciar()
                guardado()
            }
            else -> Unit
        }
    }

    Scaffold(
        containerColor = Fundo,
        topBar = {
            TopAppBar(
                title = { Text("Configurar a trotinete") },
                navigationIcon = {
                    voltar?.let { IconButton(onClick = it) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Voltar") } }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Fundo),
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Cartao(titulo = "1. A tua trotinete") {
                Text(
                    "Lê o QR do autocolante da trotinete (por baixo da plataforma ou no guiador).",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp,
                )
                OutlinedButton(
                    onClick = {
                        lerQr(context, { texto ->
                            val lido = lerQrTrotinete(texto)
                            if (lido == null) avisoQr = "Este QR não parece ser de uma trotinete Xiaomi" else {
                                qr = lido
                                avisoQr = null
                            }
                        }, { avisoQr = it })
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.QrCodeScanner, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Ler o QR da trotinete")
                }
                var link by remember { mutableStateOf("") }
                OutlinedTextField(
                    value = link,
                    onValueChange = { texto ->
                        link = texto
                        val lido = lerQrTrotinete(texto)
                        if (lido != null) {
                            qr = lido
                            avisoQr = null
                        } else if (texto.isNotBlank()) {
                            avisoQr = "Este link não parece ser de uma trotinete Xiaomi"
                        }
                    },
                    label = { Text("ou cola aqui o link do QR (home.mi.com/…)") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                qr?.let {
                    Text("MAC: ${it.mac ?: "—"}", fontWeight = FontWeight.Medium)
                    if (it.did != null) Text("Identificada na conta Xiaomi ✓", color = Verde, fontSize = 13.sp)
                }
                avisoQr?.let { Text(it, color = Ambar, fontSize = 13.sp) }
            }

            Cartao(titulo = "2. Chave da conta Xiaomi") {
                Text(
                    "Só uma vez: a app vai buscar a chave da trotinete à tua conta. O login é feito na página da Xiaomi.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp,
                )
                when (val p = passo) {
                    Passo.Inicio, Passo.Concluido -> Button(
                        onClick = { Configuracao.entrar(defs, qr) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Entrar com a conta Xiaomi") }

                    is Passo.AbrirLogin -> {
                        Progresso("Inicia sessão na página da Xiaomi e volta aqui…")
                        TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(p.url))) }) {
                            Text("Abrir a página de novo")
                        }
                    }

                    Passo.AIniciar -> Progresso("A contactar a Xiaomi…")

                    Passo.AProcurar -> Progresso("A procurar a trotinete na tua conta…")

                    is Passo.Escolher -> {
                        Text("Qual é a tua?")
                        p.trotinetes.forEach { t ->
                            OutlinedButton(onClick = { Configuracao.escolher(defs, t) }, modifier = Modifier.fillMaxWidth()) {
                                Text("${t.nome ?: t.modelo} · ${t.mac ?: "sem MAC"}")
                            }
                        }
                    }

                    is Passo.PedirPin -> {
                        var pin by remember { mutableStateOf("") }
                        Text("A chave está protegida pelo PIN que definiste para a trotinete na Mi Home.")
                        OutlinedTextField(
                            value = pin, onValueChange = { pin = it.filter(Char::isDigit) },
                            label = { Text("PIN da trotinete") },
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                            singleLine = true, modifier = Modifier.fillMaxWidth(),
                        )
                        Button(
                            onClick = { Configuracao.usarPin(defs, pin) },
                            enabled = pin.isNotEmpty(),
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Guardar e ligar") }
                    }

                    is Passo.Erro -> {
                        Text(p.mensagem, color = Vermelho, fontSize = 14.sp)
                        Button(onClick = { Configuracao.entrar(defs, qr) }, modifier = Modifier.fillMaxWidth()) {
                            Text("Tentar de novo")
                        }
                    }
                }
                if (Configuracao.podeRepetirPin && passo == Passo.Inicio) {
                    TextButton(onClick = { Configuracao.repetirPin() }) { Text("O PIN estava errado? Escrever outro") }
                }
            }

            TextButton(onClick = { avancado = !avancado }) {
                Text(if (avancado) "Esconder opção avançada" else "Já tenho a chave (avançado)")
            }
            if (avancado) ChaveManual(defs, qr?.mac, guardado)
        }
    }
}

@Composable
private fun ChaveManual(defs: Definicoes, macInicial: String?, guardado: () -> Unit) {
    var mac by remember { mutableStateOf(macInicial ?: defs.mac ?: "") }
    var chave by remember { mutableStateOf("") }
    val macOk = formatarMac(mac) != null
    val chaveLimpa = chave.trim().lowercase()
    Cartao(titulo = "Chave manual") {
        Text("A chave obtida no PC com pc/chave.py (64 caracteres).", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
        OutlinedTextField(
            value = mac, onValueChange = { mac = it }, label = { Text("MAC") },
            isError = mac.isNotEmpty() && !macOk, singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = chave, onValueChange = { chave = it }, label = { Text("Chave") },
            isError = chave.isNotEmpty() && !FORMATO_CHAVE.matches(chaveLimpa),
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = {
                defs.mac = formatarMac(mac)
                defs.ltmk = chaveLimpa.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                guardado()
            },
            enabled = macOk && FORMATO_CHAVE.matches(chaveLimpa),
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Guardar e ligar") }
    }
}

@Composable
private fun Progresso(texto: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(12.dp))
        Text(texto)
    }
}
