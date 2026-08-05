# Nodo Fase 0-1 — Esqueleto multi-módulo + motor llama.cpp + chat local

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** App Android multi-módulo que carga un GGUF local con llama.cpp (JNI) y permite chatear con streaming, medida en tok/s reales en el S25 Ultra.

**Architecture:** Multi-módulo Gradle (`:app`, `:core:inference`, `:feature:chat`). `InferenceEngine` es la abstracción (patrón `AiProvider` de RevScope); `LlamaCppEngine` la implementa sobre un wrapper JNI propio (modelado en SmolChat-Android, Apache-2.0) con llama.cpp como submódulo git. UI Compose + Hilt, nav de 5 tabs (solo Chat funcional en Fase 1).

**Tech Stack:** Kotlin 2.1.x, Compose (BOM 2025.x), Hilt 2.55, AGP 8.9.x + Gradle 8.11.1 (cacheado), NDK 27.1.12297006, CMake 3.22.1, llama.cpp (submódulo, tag de release pineado), JUnit4 + Turbine + coroutines-test.

## Global Constraints

- Código, commits y docs **en español**; commits por capa con prefijo (`feat:`, `docs:`, `chore:`); **nunca** `Co-Authored-By`.
- Package raíz: `com.santiquiroz.nodo`. applicationId: `com.santiquiroz.nodo`.
- minSdk **31**, compileSdk/targetSdk **36**, abiFilters **arm64-v8a** solamente.
- `use_mmap = true` SIEMPRE al cargar modelos (OOM garantizado sin mmap).
- Gradle sin wrapper global: usar `~/.gradle/wrapper/dists/gradle-8.11.1-bin/*/gradle-8.11.1/bin/gradle -p c:/personal/nodo` hasta generar wrapper (Task 1 lo genera; después `./gradlew`).
- Device: `adb -s adb-R5CY82F28DM-0ahBFS._adb-tls-connect._tcp` (S25 Ultra, API 36).
- Licencia MIT; atribución a SmolChat-Android en NOTICE cuando se copie estructura del wrapper.
- Versiones de libs: las de abajo son las investigadas — **verificar la última estable al ejecutar Task 1** y ajustar el catálogo (no re-investigar stack).
- **Riesgo #1 conocido:** la API C de llama.cpp cambia entre releases. El código JNI de este plan sigue la API post-refactor 2025 (`llama_model_load_from_file`, `llama_init_from_model`, `llama_sampler_chain_*`, `llama_memory_clear`, `llama_vocab_*`). Al ejecutar Task 6: si un símbolo no existe en el header del tag pineado, buscar el equivalente en `third_party/llama.cpp/include/llama.h` y en `examples/simple-chat/` — la estructura del wrapper no cambia.

---

## Estructura de archivos final

```
nodo/
├─ settings.gradle.kts
├─ build.gradle.kts
├─ gradle.properties
├─ gradle/libs.versions.toml
├─ gradle/wrapper/…            (generado Task 1)
├─ .gitmodules                  (Task 5)
├─ third_party/llama.cpp        (submódulo, Task 5)
├─ app/
│  ├─ build.gradle.kts
│  └─ src/main/
│     ├─ AndroidManifest.xml
│     └─ java/com/santiquiroz/nodo/
│        ├─ NodoApp.kt
│        ├─ MainActivity.kt
│        └─ ui/
│           ├─ theme/Theme.kt
│           ├─ nav/NodoNavHost.kt
│           └─ screens/PlaceholderScreen.kt
├─ core/inference/
│  ├─ build.gradle.kts
│  └─ src/main/
│     ├─ cpp/CMakeLists.txt
│     ├─ cpp/nodo_llama.cpp
│     └─ java/com/santiquiroz/nodo/core/inference/
│        ├─ EngineModels.kt
│        ├─ InferenceEngine.kt
│        ├─ LlamaNative.kt
│        ├─ LlamaCppEngine.kt
│        └─ InferenceModule.kt
└─ feature/chat/
   ├─ build.gradle.kts
   ├─ src/main/java/com/santiquiroz/nodo/feature/chat/
   │  ├─ ModelFilesRepository.kt
   │  ├─ ChatViewModel.kt
   │  └─ ChatScreen.kt
   └─ src/test/java/com/santiquiroz/nodo/feature/chat/
      ├─ FakeInferenceEngine.kt
      └─ ChatViewModelTest.kt
```

---

## FASE 0

### Task 1: Raíz Gradle + wrapper

**Files:**
- Create: `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`, `gradle/libs.versions.toml`
- Modify: `.gitignore`

**Interfaces:**
- Produces: catálogo `libs` con alias usados por Tasks 2, 4, 8 (`libs.plugins.android.application`, `libs.plugins.kotlin.android`, `libs.plugins.kotlin.compose`, `libs.plugins.hilt`, `libs.plugins.ksp`, `libs.androidx.compose.bom`, etc.).

- [ ] **Step 1: `gradle/libs.versions.toml`** (verificar últimas estables antes de fijar)

```toml
[versions]
agp = "8.9.1"
kotlin = "2.1.10"
ksp = "2.1.10-1.0.31"
hilt = "2.55"
composeBom = "2025.02.00"
activityCompose = "1.10.0"
navigationCompose = "2.8.7"
hiltNavigationCompose = "1.2.0"
lifecycle = "2.8.7"
coroutines = "1.10.1"
coreKtx = "1.15.0"
junit = "4.13.2"
turbine = "1.2.0"

[libraries]
androidx-core-ktx = { group = "androidx.core", name = "core-ktx", version.ref = "coreKtx" }
androidx-activity-compose = { group = "androidx.activity", name = "activity-compose", version.ref = "activityCompose" }
androidx-compose-bom = { group = "androidx.compose", name = "compose-bom", version.ref = "composeBom" }
androidx-compose-ui = { group = "androidx.compose.ui", name = "ui" }
androidx-compose-material3 = { group = "androidx.compose.material3", name = "material3" }
androidx-compose-icons-extended = { group = "androidx.compose.material", name = "material-icons-extended" }
androidx-compose-ui-tooling-preview = { group = "androidx.compose.ui", name = "ui-tooling-preview" }
androidx-navigation-compose = { group = "androidx.navigation", name = "navigation-compose", version.ref = "navigationCompose" }
androidx-hilt-navigation-compose = { group = "androidx.hilt", name = "hilt-navigation-compose", version.ref = "hiltNavigationCompose" }
androidx-lifecycle-viewmodel-compose = { group = "androidx.lifecycle", name = "lifecycle-viewmodel-compose", version.ref = "lifecycle" }
androidx-lifecycle-runtime-compose = { group = "androidx.lifecycle", name = "lifecycle-runtime-compose", version.ref = "lifecycle" }
hilt-android = { group = "com.google.dagger", name = "hilt-android", version.ref = "hilt" }
hilt-compiler = { group = "com.google.dagger", name = "hilt-compiler", version.ref = "hilt" }
kotlinx-coroutines-core = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-core", version.ref = "coroutines" }
kotlinx-coroutines-test = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-test", version.ref = "coroutines" }
junit = { group = "junit", name = "junit", version.ref = "junit" }
turbine = { group = "app.cash.turbine", name = "turbine", version.ref = "turbine" }

[plugins]
android-application = { id = "com.android.application", version.ref = "agp" }
android-library = { id = "com.android.library", version.ref = "agp" }
kotlin-android = { id = "org.jetbrains.kotlin.android", version.ref = "kotlin" }
kotlin-compose = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
hilt = { id = "com.google.dagger.hilt.android", version.ref = "hilt" }
ksp = { id = "com.google.devtools.ksp", version.ref = "ksp" }
```

- [ ] **Step 2: `settings.gradle.kts`**

```kotlin
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "nodo"
include(":app", ":core:inference", ":feature:chat")
```

- [ ] **Step 3: `build.gradle.kts` raíz**

```kotlin
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.ksp) apply false
}
```

- [ ] **Step 4: `gradle.properties`**

```properties
org.gradle.jvmargs=-Xmx4g -Dfile.encoding=UTF-8
org.gradle.caching=true
org.gradle.parallel=true
android.useAndroidX=true
kotlin.code.style=official
```

- [ ] **Step 5: `.gitignore`** — añadir (mantener lo existente):

```
.gradle/
build/
local.properties
.idea/
*.iml
.kotlin/
```

- [ ] **Step 6: generar wrapper** (los módulos aún no existen — comentar temporalmente la línea `include(...)` de settings, generar, descomentar):

```bash
GRADLE=$(ls -d ~/.gradle/wrapper/dists/gradle-8.11.1-bin/*/gradle-8.11.1/bin/gradle | head -1)
"$GRADLE" wrapper --gradle-version 8.11.1 -p /c/personal/nodo
```

Expected: crea `gradlew`, `gradlew.bat`, `gradle/wrapper/`. Verificar: `cd /c/personal/nodo && ./gradlew help` → BUILD SUCCESSFUL (con include comentado).

- [ ] **Step 7: Commit**

```bash
git -C /c/personal/nodo add -A
git -C /c/personal/nodo commit -m "chore: raíz Gradle multi-módulo — catálogo de versiones, settings y wrapper 8.11.1"
```

---

### Task 2: Módulo `:app` — Compose + Hilt + tema oscuro + nav 5 tabs

**Files:**
- Create: `app/build.gradle.kts`, `app/src/main/AndroidManifest.xml`, `NodoApp.kt`, `MainActivity.kt`, `ui/theme/Theme.kt`, `ui/nav/NodoNavHost.kt`, `ui/screens/PlaceholderScreen.kt`

**Interfaces:**
- Consumes: catálogo `libs` (Task 1).
- Produces: `NodoNavHost` con ruta `"chat"` que Task 9 reemplaza por `ChatScreen`; `NodoTheme(content: @Composable () -> Unit)`.

Nota: `:app` aún NO depende de `:core:inference`/`:feature:chat` (llegan en Fase 1). Para que `settings.gradle.kts` no falle, crear también los dos `build.gradle.kts` mínimos de esos módulos en esta task (Step 6).

- [ ] **Step 1: `app/build.gradle.kts`**

```kotlin
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.santiquiroz.nodo"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.santiquiroz.nodo"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        ndk { abiFilters += "arm64-v8a" }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.icons.extended)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
}
```

- [ ] **Step 2: `AndroidManifest.xml`**

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <application
        android:name=".NodoApp"
        android:label="Nodo"
        android:icon="@android:drawable/ic_menu_manage"
        android:theme="@android:style/Theme.Material.NoActionBar">
        <activity
            android:name=".MainActivity"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
```

(Icono placeholder del sistema en Fase 0-1; icono propio va en Fase 5.)

- [ ] **Step 3: `NodoApp.kt` + `MainActivity.kt`**

```kotlin
// NodoApp.kt
package com.santiquiroz.nodo

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class NodoApp : Application()
```

```kotlin
// MainActivity.kt
package com.santiquiroz.nodo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.santiquiroz.nodo.ui.nav.NodoNavHost
import com.santiquiroz.nodo.ui.theme.NodoTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            NodoTheme {
                NodoNavHost()
            }
        }
    }
}
```

- [ ] **Step 4: `ui/theme/Theme.kt`** — herramienta de desarrollador, oscura, técnica:

```kotlin
package com.santiquiroz.nodo.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp

private val Fondo = Color(0xFF0B0E14)
private val Superficie = Color(0xFF131822)
private val SuperficieAlta = Color(0xFF1B2230)
private val Primario = Color(0xFF57D9A3)      // verde terminal
private val Secundario = Color(0xFF7AA2F7)    // azul técnico
private val TextoPrincipal = Color(0xFFE6E9EF) // contraste >4.5:1 sobre Fondo
private val TextoSecundario = Color(0xFF8B93A7)
private val ErrorRojo = Color(0xFFF7768E)

private val EsquemaOscuro = darkColorScheme(
    primary = Primario,
    onPrimary = Fondo,
    secondary = Secundario,
    onSecondary = Fondo,
    background = Fondo,
    onBackground = TextoPrincipal,
    surface = Superficie,
    onSurface = TextoPrincipal,
    surfaceVariant = SuperficieAlta,
    onSurfaceVariant = TextoSecundario,
    error = ErrorRojo,
    onError = Fondo,
)

val TipografiaMono = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp)

@Composable
fun NodoTheme(content: @Composable () -> Unit) {
    // v1 es dark-only: estética de herramienta de desarrollador
    MaterialTheme(
        colorScheme = EsquemaOscuro,
        typography = Typography(),
        content = content,
    )
}
```

- [ ] **Step 5: `ui/nav/NodoNavHost.kt` + `ui/screens/PlaceholderScreen.kt`**

```kotlin
// PlaceholderScreen.kt
package com.santiquiroz.nodo.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

@Composable
fun PlaceholderScreen(titulo: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = "$titulo — próximamente",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
```

```kotlin
// NodoNavHost.kt
package com.santiquiroz.nodo.ui.nav

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.santiquiroz.nodo.ui.screens.PlaceholderScreen

data class Destino(val ruta: String, val titulo: String, val icono: ImageVector)

val destinos = listOf(
    Destino("modelos", "Modelos", Icons.Outlined.Folder),
    Destino("explorar", "Explorar", Icons.Outlined.Search),
    Destino("servidor", "Servidor", Icons.Outlined.Dns),
    Destino("chat", "Chat", Icons.Outlined.ChatBubbleOutline),
    Destino("ajustes", "Ajustes", Icons.Outlined.Settings),
)

@Composable
fun NodoNavHost() {
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val rutaActual = backStack?.destination?.route

    Scaffold(
        bottomBar = {
            NavigationBar {
                destinos.forEach { destino ->
                    NavigationBarItem(
                        selected = rutaActual == destino.ruta,
                        onClick = {
                            navController.navigate(destino.ruta) {
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(destino.icono, contentDescription = destino.titulo) },
                        label = { Text(destino.titulo) },
                    )
                }
            }
        }
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = "modelos",
            modifier = Modifier.padding(padding),
        ) {
            destinos.forEach { destino ->
                composable(destino.ruta) { PlaceholderScreen(destino.titulo) }
            }
        }
    }
}
```

- [ ] **Step 6: módulos vacíos para que settings resuelva** — `core/inference/build.gradle.kts` y `feature/chat/build.gradle.kts` mínimos (Fase 1 los completa):

```kotlin
// core/inference/build.gradle.kts  (y análogo feature/chat con namespace .feature.chat)
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}
android {
    namespace = "com.santiquiroz.nodo.core.inference"
    compileSdk = 36
    defaultConfig { minSdk = 31 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
```

- [ ] **Step 7: Build verde**

```bash
cd /c/personal/nodo && ./gradlew :app:assembleDebug
```

Expected: BUILD SUCCESSFUL, APK en `app/build/outputs/apk/debug/`.

- [ ] **Step 8: Commit**

```bash
git -C /c/personal/nodo add -A
git -C /c/personal/nodo commit -m "feat: esqueleto :app — Compose, Hilt, tema oscuro técnico y nav de 5 tabs con placeholders"
```

---

### Task 3: Gate en dispositivo + push

- [ ] **Step 1: instalar y lanzar en S25 Ultra**

```bash
SERIAL=adb-R5CY82F28DM-0ahBFS._adb-tls-connect._tcp
adb -s $SERIAL install -r /c/personal/nodo/app/build/outputs/apk/debug/app-debug.apk
adb -s $SERIAL shell am start -n com.santiquiroz.nodo/.MainActivity
adb -s $SERIAL exec-out screencap -p > /tmp/nodo-fase0.png   # verificar visualmente nav de 5 tabs
```

Expected: app abre, 5 tabs navegables, sin crash (`adb logcat -d | grep -i "FATAL\|AndroidRuntime"` limpio).

- [ ] **Step 2: push a GitHub**

```bash
git -C /c/personal/nodo push origin master
```

---

## FASE 1

### Task 4: `:core:inference` — contrato del motor (Kotlin puro)

**Files:**
- Create: `core/inference/src/main/java/com/santiquiroz/nodo/core/inference/EngineModels.kt`, `InferenceEngine.kt`
- Modify: `core/inference/build.gradle.kts` (añadir coroutines)

**Interfaces:**
- Produces (Tasks 7, 8, 9 dependen de esto — nombres exactos):
  - `interface InferenceEngine { val state: StateFlow<EngineState>; suspend fun load(modelPath: String, config: EngineConfig); suspend fun unload(); fun generate(messages: List<ChatMessage>, params: GenerationParams): Flow<GenerationEvent> }`
  - `EngineState` = `Idle | Loading(modelName) | Ready(model: ModelInfo) | Error(message)`
  - `GenerationEvent` = `Token(text: String) | Done(stats: GenerationStats) | Failure(message: String)`

- [ ] **Step 1: `EngineModels.kt`**

```kotlin
package com.santiquiroz.nodo.core.inference

data class EngineConfig(
    val contextLength: Int = 4096,
    val threads: Int = 6,
)

data class GenerationParams(
    val temperature: Float = 0.8f,
    val minP: Float = 0.05f,
    val maxTokens: Int = 1024,
)

data class ChatMessage(val role: Role, val content: String) {
    enum class Role(val wire: String) { SYSTEM("system"), USER("user"), ASSISTANT("assistant") }
}

data class ModelInfo(val name: String, val path: String, val sizeBytes: Long)

data class GenerationStats(
    val promptTokens: Int,
    val generatedTokens: Int,
    val timeToFirstTokenMs: Long,
    val totalTimeMs: Long,
) {
    val tokensPerSecond: Double
        get() = if (totalTimeMs > timeToFirstTokenMs && generatedTokens > 1)
            (generatedTokens - 1) * 1000.0 / (totalTimeMs - timeToFirstTokenMs) else 0.0
}

sealed interface EngineState {
    data object Idle : EngineState
    data class Loading(val modelName: String) : EngineState
    data class Ready(val model: ModelInfo) : EngineState
    data class Error(val message: String) : EngineState
}

sealed interface GenerationEvent {
    data class Token(val text: String) : GenerationEvent
    data class Done(val stats: GenerationStats) : GenerationEvent
    data class Failure(val message: String) : GenerationEvent
}
```

- [ ] **Step 2: `InferenceEngine.kt`**

```kotlin
package com.santiquiroz.nodo.core.inference

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface InferenceEngine {
    val state: StateFlow<EngineState>
    suspend fun load(modelPath: String, config: EngineConfig = EngineConfig())
    suspend fun unload()
    fun generate(messages: List<ChatMessage>, params: GenerationParams = GenerationParams()): Flow<GenerationEvent>
}
```

- [ ] **Step 3: dependencias del módulo** — en `core/inference/build.gradle.kts` añadir:

```kotlin
dependencies {
    implementation(libs.kotlinx.coroutines.core)
}
```

- [ ] **Step 4: build + commit**

```bash
./gradlew :core:inference:assembleDebug
git add -A && git commit -m "feat: contrato InferenceEngine — estados, eventos de generación y stats en :core:inference"
```

---

### Task 5: llama.cpp como submódulo + JNI compilando (stub)

**Files:**
- Create: `.gitmodules` (via `git submodule add`), `core/inference/src/main/cpp/CMakeLists.txt`, `core/inference/src/main/cpp/nodo_llama.cpp` (stub), `core/inference/src/main/java/.../LlamaNative.kt`
- Modify: `core/inference/build.gradle.kts`

**Interfaces:**
- Produces: `LlamaNative` (object interno) con `external fun systemInfo(): String` — Task 6 le añade el resto.

- [ ] **Step 1: submódulo pineado a release**

```bash
cd /c/personal/nodo
git submodule add https://github.com/ggml-org/llama.cpp third_party/llama.cpp
cd third_party/llama.cpp
TAG=$(git tag --sort=-creatordate | grep -E '^b[0-9]+$' | head -1)
git checkout "$TAG"
echo "Tag pineado: $TAG"   # registrar este tag en el commit
```

- [ ] **Step 2: `core/inference/src/main/cpp/CMakeLists.txt`**

```cmake
cmake_minimum_required(VERSION 3.22)
project(nodo_llama)

set(LLAMA_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../../third_party/llama.cpp)

set(BUILD_SHARED_LIBS OFF)
set(LLAMA_BUILD_TESTS OFF)
set(LLAMA_BUILD_EXAMPLES OFF)
set(LLAMA_BUILD_SERVER OFF)
set(LLAMA_BUILD_TOOLS OFF)
set(LLAMA_CURL OFF)
set(GGML_OPENMP OFF)

add_subdirectory(${LLAMA_DIR} llama_build)

add_library(nodo_llama SHARED nodo_llama.cpp)
target_link_libraries(nodo_llama PRIVATE llama ggml log android)
target_include_directories(nodo_llama PRIVATE ${LLAMA_DIR}/include ${LLAMA_DIR}/ggml/include)
# Soporte páginas de 16KB (Android 15+)
target_link_options(nodo_llama PRIVATE "-Wl,-z,max-page-size=16384")
```

- [ ] **Step 3: stub `nodo_llama.cpp`**

```cpp
#include <jni.h>
#include <string>
#include "llama.h"

extern "C" JNIEXPORT jstring JNICALL
Java_com_santiquiroz_nodo_core_inference_LlamaNative_systemInfo(JNIEnv* env, jobject) {
    return env->NewStringUTF(llama_print_system_info());
}
```

- [ ] **Step 4: `LlamaNative.kt`**

```kotlin
package com.santiquiroz.nodo.core.inference

internal object LlamaNative {
    init {
        System.loadLibrary("nodo_llama")
    }

    external fun systemInfo(): String
}
```

- [ ] **Step 5: `core/inference/build.gradle.kts`** — bloque android gana:

```kotlin
android {
    // ...existente...
    defaultConfig {
        minSdk = 31
        ndk { abiFilters += "arm64-v8a" }
        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_STL=c++_shared", "-DCMAKE_BUILD_TYPE=Release")
                cppFlags += "-std=c++17"
            }
        }
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    ndkVersion = "27.1.12297006"
}
```

- [ ] **Step 6: build nativo verde** (primera compilación de llama.cpp tarda varios minutos — usar Bash `run_in_background` si supera 8 min):

```bash
./gradlew :core:inference:assembleDebug
```

Expected: BUILD SUCCESSFUL, `libnodo_llama.so` en el AAR. Si falla por flags CMake del tag: revisar nombres de opciones en `third_party/llama.cpp/CMakeLists.txt` (p. ej. `LLAMA_BUILD_TOOLS` puede no existir en tags viejos — quitar la línea).

- [ ] **Step 7: Commit** (incluir el tag pineado en el mensaje)

```bash
git add -A && git commit -m "feat: llama.cpp como submódulo (tag <TAG>) + puente JNI compilando en :core:inference"
```

---

### Task 6: JNI completo — cargar, formatear chat, generar token a token

**Files:**
- Modify: `core/inference/src/main/cpp/nodo_llama.cpp`, `LlamaNative.kt`

**Interfaces:**
- Produces (Task 7 consume exactamente esto):

```kotlin
internal object LlamaNative {
    external fun systemInfo(): String
    external fun load(path: String, nCtx: Int, nThreads: Int, temp: Float, minP: Float): Long
    external fun free(handle: Long)
    external fun formatChat(handle: Long, roles: Array<String>, texts: Array<String>): String?
    external fun start(handle: Long, prompt: String): Int   // >=0 tokens de prompt; -1 error
    external fun next(handle: Long): String?                 // null = EOS/error; "" = bytes UTF-8 incompletos retenidos
}
```

- [ ] **Step 1: reescribir `nodo_llama.cpp`** (API post-2025; contrastar contra `include/llama.h` del tag y `examples/simple-chat/`):

```cpp
#include <jni.h>
#include <string>
#include <vector>
#include <android/log.h>
#include "llama.h"

#define LOG_TAG "nodo_llama"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

struct NodoSession {
    llama_model* model = nullptr;
    llama_context* ctx = nullptr;
    llama_sampler* smpl = nullptr;
    const llama_vocab* vocab = nullptr;
    std::string pending;   // bytes UTF-8 incompletos entre tokens
    bool generating = false;
};

// Devuelve el prefijo UTF-8 válido de pending y deja el resto retenido
static std::string extraer_utf8_valido(std::string& pending) {
    size_t valid = 0;
    size_t i = 0;
    while (i < pending.size()) {
        unsigned char c = pending[i];
        size_t len = (c < 0x80) ? 1 : (c >> 5) == 0x6 ? 2 : (c >> 4) == 0xE ? 3 : (c >> 3) == 0x1E ? 4 : 0;
        if (len == 0) { valid = ++i; continue; }          // byte inválido: saltarlo
        if (i + len > pending.size()) break;               // secuencia incompleta: retener
        i += len;
        valid = i;
    }
    std::string out = pending.substr(0, valid);
    pending.erase(0, valid);
    return out;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_santiquiroz_nodo_core_inference_LlamaNative_systemInfo(JNIEnv* env, jobject) {
    return env->NewStringUTF(llama_print_system_info());
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_santiquiroz_nodo_core_inference_LlamaNative_load(
        JNIEnv* env, jobject, jstring jpath, jint nCtx, jint nThreads, jfloat temp, jfloat minP) {
    llama_backend_init();
    const char* path = env->GetStringUTFChars(jpath, nullptr);
    llama_model_params mp = llama_model_default_params();
    mp.use_mmap = true;   // obligatorio: sin mmap un 3B Q4 revienta el heap
    llama_model* model = llama_model_load_from_file(path, mp);
    env->ReleaseStringUTFChars(jpath, path);
    if (!model) { LOGE("load: fallo llama_model_load_from_file"); return 0; }

    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = (uint32_t) nCtx;
    cp.n_batch = 512;
    cp.n_threads = nThreads;
    cp.n_threads_batch = nThreads;
    llama_context* ctx = llama_init_from_model(model, cp);
    if (!ctx) { llama_model_free(model); LOGE("load: fallo llama_init_from_model"); return 0; }

    auto* s = new NodoSession();
    s->model = model;
    s->ctx = ctx;
    s->vocab = llama_model_get_vocab(model);
    s->smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(s->smpl, llama_sampler_init_min_p(minP, 1));
    llama_sampler_chain_add(s->smpl, llama_sampler_init_temp(temp));
    llama_sampler_chain_add(s->smpl, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));
    return reinterpret_cast<jlong>(s);
}

extern "C" JNIEXPORT void JNICALL
Java_com_santiquiroz_nodo_core_inference_LlamaNative_free(JNIEnv*, jobject, jlong handle) {
    auto* s = reinterpret_cast<NodoSession*>(handle);
    if (!s) return;
    if (s->smpl) llama_sampler_free(s->smpl);
    if (s->ctx) llama_free(s->ctx);
    if (s->model) llama_model_free(s->model);
    delete s;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_santiquiroz_nodo_core_inference_LlamaNative_formatChat(
        JNIEnv* env, jobject, jlong handle, jobjectArray jroles, jobjectArray jtexts) {
    auto* s = reinterpret_cast<NodoSession*>(handle);
    if (!s) return nullptr;
    const char* tmpl = llama_model_chat_template(s->model, nullptr);
    if (!tmpl) return nullptr;   // sin plantilla: Kotlin usa fallback ChatML

    jsize n = env->GetArrayLength(jroles);
    std::vector<std::string> roles(n), texts(n);
    std::vector<llama_chat_message> msgs(n);
    for (jsize i = 0; i < n; i++) {
        auto jr = (jstring) env->GetObjectArrayElement(jroles, i);
        auto jt = (jstring) env->GetObjectArrayElement(jtexts, i);
        const char* r = env->GetStringUTFChars(jr, nullptr);
        const char* t = env->GetStringUTFChars(jt, nullptr);
        roles[i] = r; texts[i] = t;
        env->ReleaseStringUTFChars(jr, r);
        env->ReleaseStringUTFChars(jt, t);
        msgs[i] = { roles[i].c_str(), texts[i].c_str() };
    }
    std::vector<char> buf(65536);
    int len = llama_chat_apply_template(tmpl, msgs.data(), n, true, buf.data(), (int) buf.size());
    if (len > (int) buf.size()) {
        buf.resize(len);
        len = llama_chat_apply_template(tmpl, msgs.data(), n, true, buf.data(), (int) buf.size());
    }
    if (len < 0) return nullptr;
    return env->NewStringUTF(std::string(buf.data(), len).c_str());
}

extern "C" JNIEXPORT jint JNICALL
Java_com_santiquiroz_nodo_core_inference_LlamaNative_start(
        JNIEnv* env, jobject, jlong handle, jstring jprompt) {
    auto* s = reinterpret_cast<NodoSession*>(handle);
    if (!s) return -1;
    s->pending.clear();
    s->generating = false;

    // Conversación completa re-decodificada cada turno (v1): limpiar KV
    llama_memory_clear(llama_get_memory(s->ctx), true);

    const char* prompt = env->GetStringUTFChars(jprompt, nullptr);
    int text_len = (int) strlen(prompt);
    int n_tokens = -llama_tokenize(s->vocab, prompt, text_len, nullptr, 0, true, true);
    if (n_tokens <= 0) { env->ReleaseStringUTFChars(jprompt, prompt); return -1; }
    std::vector<llama_token> tokens(n_tokens);
    llama_tokenize(s->vocab, prompt, text_len, tokens.data(), n_tokens, true, true);
    env->ReleaseStringUTFChars(jprompt, prompt);

    int n_batch = 512;
    for (int i = 0; i < n_tokens; i += n_batch) {
        int chunk = std::min(n_batch, n_tokens - i);
        llama_batch batch = llama_batch_get_one(tokens.data() + i, chunk);
        if (llama_decode(s->ctx, batch) != 0) { LOGE("start: llama_decode fallo en prompt"); return -1; }
    }
    s->generating = true;
    return n_tokens;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_santiquiroz_nodo_core_inference_LlamaNative_next(JNIEnv* env, jobject, jlong handle) {
    auto* s = reinterpret_cast<NodoSession*>(handle);
    if (!s || !s->generating) return nullptr;

    llama_token tok = llama_sampler_sample(s->smpl, s->ctx, -1);
    if (llama_vocab_is_eog(s->vocab, tok)) {
        s->generating = false;
        return nullptr;
    }
    char buf[256];
    int n = llama_token_to_piece(s->vocab, tok, buf, sizeof(buf), 0, true);
    if (n > 0) s->pending.append(buf, n);

    llama_batch batch = llama_batch_get_one(&tok, 1);
    if (llama_decode(s->ctx, batch) != 0) {
        s->generating = false;
        LOGE("next: llama_decode fallo");
        return nullptr;
    }
    return env->NewStringUTF(extraer_utf8_valido(s->pending).c_str());
}
```

- [ ] **Step 2: actualizar `LlamaNative.kt`** con las firmas del bloque Interfaces.

- [ ] **Step 3: build verde**

```bash
./gradlew :core:inference:assembleDebug
```

Si un símbolo no compila (drift de API): buscar reemplazo en `third_party/llama.cpp/include/llama.h` — candidatos conocidos: `llama_memory_clear(llama_get_memory(ctx), true)` ↔ `llama_kv_self_clear(ctx)` en tags anteriores.

- [ ] **Step 4: Commit**

```bash
git add -A && git commit -m "feat: puente JNI completo — carga con mmap, plantilla de chat, prefill y generación token a token con retención UTF-8"
```

---

### Task 7: `LlamaCppEngine` — Kotlin sobre JNI

**Files:**
- Create: `LlamaCppEngine.kt`, `InferenceModule.kt`
- Modify: `core/inference/build.gradle.kts` (Hilt)

**Interfaces:**
- Consumes: `LlamaNative` (Task 6), contrato (Task 4).
- Produces: `LlamaCppEngine : InferenceEngine` (@Singleton), `InferenceModule` (@Binds InferenceEngine).

- [ ] **Step 1: Hilt en el módulo** — añadir a `core/inference/build.gradle.kts`:

```kotlin
plugins {
    // ...existentes...
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}
dependencies {
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
}
```

- [ ] **Step 2: `LlamaCppEngine.kt`**

```kotlin
package com.santiquiroz.nodo.core.inference

import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LlamaCppEngine @Inject constructor() : InferenceEngine {

    // llama_context NO es thread-safe: todas las llamadas JNI confinadas a un solo hilo
    private val llamaDispatcher =
        Executors.newSingleThreadExecutor { r -> Thread(r, "nodo-llama") }.asCoroutineDispatcher()
    private val singleFlight = Mutex()

    private var handle = 0L
    private var config = EngineConfig()

    private val _state = MutableStateFlow<EngineState>(EngineState.Idle)
    override val state = _state.asStateFlow()

    override suspend fun load(modelPath: String, config: EngineConfig) = withContext(llamaDispatcher) {
        val file = File(modelPath)
        if (handle != 0L) {
            LlamaNative.free(handle)
            handle = 0L
        }
        _state.value = EngineState.Loading(file.name)
        this@LlamaCppEngine.config = config
        val params = GenerationParams()
        val h = LlamaNative.load(modelPath, config.contextLength, config.threads, params.temperature, params.minP)
        _state.value = if (h == 0L) {
            EngineState.Error("No se pudo cargar ${file.name}")
        } else {
            handle = h
            EngineState.Ready(ModelInfo(file.name, modelPath, file.length()))
        }
    }

    override suspend fun unload() = withContext(llamaDispatcher) {
        if (handle != 0L) {
            LlamaNative.free(handle)
            handle = 0L
        }
        _state.value = EngineState.Idle
    }

    override fun generate(messages: List<ChatMessage>, params: GenerationParams): Flow<GenerationEvent> = flow {
        singleFlight.withLock {
            if (handle == 0L) {
                emit(GenerationEvent.Failure("No hay modelo cargado"))
                return@withLock
            }
            val prompt = formatearPrompt(messages)
            val inicio = System.nanoTime()
            val promptTokens = LlamaNative.start(handle, prompt)
            if (promptTokens < 0) {
                emit(GenerationEvent.Failure("Fallo al procesar el prompt"))
                return@withLock
            }
            var generados = 0
            var primerTokenMs = 0L
            while (generados < params.maxTokens) {
                val pieza = LlamaNative.next(handle) ?: break
                generados++
                if (primerTokenMs == 0L) primerTokenMs = (System.nanoTime() - inicio) / 1_000_000
                if (pieza.isNotEmpty()) emit(GenerationEvent.Token(pieza))
            }
            val totalMs = (System.nanoTime() - inicio) / 1_000_000
            emit(GenerationEvent.Done(GenerationStats(promptTokens, generados, primerTokenMs, totalMs)))
        }
    }.flowOn(llamaDispatcher)

    private fun formatearPrompt(messages: List<ChatMessage>): String {
        val roles = messages.map { it.role.wire }.toTypedArray()
        val texts = messages.map { it.content }.toTypedArray()
        return LlamaNative.formatChat(handle, roles, texts) ?: fallbackChatMl(messages)
    }

    // Modelos sin tokenizer.chat_template: formato ChatML genérico
    private fun fallbackChatMl(messages: List<ChatMessage>): String = buildString {
        messages.forEach { append("<|im_start|>${it.role.wire}\n${it.content}<|im_end|>\n") }
        append("<|im_start|>assistant\n")
    }
}
```

- [ ] **Step 3: `InferenceModule.kt`**

```kotlin
package com.santiquiroz.nodo.core.inference

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class InferenceModule {
    @Binds
    abstract fun bindInferenceEngine(impl: LlamaCppEngine): InferenceEngine
}
```

- [ ] **Step 4: build + commit**

```bash
./gradlew :core:inference:assembleDebug
git add -A && git commit -m "feat: LlamaCppEngine — dispatcher confinado, single-flight con Mutex, streaming por Flow y fallback ChatML"
```

---

### Task 8: `:feature:chat` — ViewModel con TDD

**Files:**
- Modify: `feature/chat/build.gradle.kts`
- Create: `ModelFilesRepository.kt`, `ChatViewModel.kt`, test: `FakeInferenceEngine.kt`, `ChatViewModelTest.kt`

**Interfaces:**
- Consumes: `InferenceEngine`, `EngineState`, `GenerationEvent`, `ChatMessage` (Task 4).
- Produces: `ChatViewModel` (@HiltViewModel) con `val uiState: StateFlow<ChatUiState>`, `fun onInputChange(String)`, `fun onSend()`, `fun onSelectModel(ModelFile)`, `fun onRefreshModels()`; `data class ModelFile(val name: String, val path: String, val sizeBytes: Long)`; `interface ModelFilesRepository { fun listar(): List<ModelFile> }`.

- [ ] **Step 1: `feature/chat/build.gradle.kts` completo**

```kotlin
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}
android {
    namespace = "com.santiquiroz.nodo.feature.chat"
    compileSdk = 36
    defaultConfig { minSdk = 31 }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation(project(":core:inference"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.icons.extended)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
}
```

- [ ] **Step 2: `ModelFilesRepository.kt`** (interfaz + impl Android; la interfaz permite fake en tests)

```kotlin
package com.santiquiroz.nodo.feature.chat

import android.content.Context
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject

data class ModelFile(val name: String, val path: String, val sizeBytes: Long)

interface ModelFilesRepository {
    fun listar(): List<ModelFile>
}

class ModelFilesRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
) : ModelFilesRepository {
    override fun listar(): List<ModelFile> {
        val dir = context.getExternalFilesDir("models") ?: return emptyList()
        if (!dir.exists()) dir.mkdirs()
        return dir.listFiles { f -> f.isFile && f.name.endsWith(".gguf") }
            .orEmpty()
            .sortedBy { it.name }
            .map { ModelFile(it.name, it.absolutePath, it.length()) }
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class ChatModule {
    @Binds
    abstract fun bindModelFilesRepository(impl: ModelFilesRepositoryImpl): ModelFilesRepository
}
```

- [ ] **Step 3 (RED): `FakeInferenceEngine.kt` + `ChatViewModelTest.kt`**

```kotlin
// src/test/java/com/santiquiroz/nodo/feature/chat/FakeInferenceEngine.kt
package com.santiquiroz.nodo.feature.chat

import com.santiquiroz.nodo.core.inference.ChatMessage
import com.santiquiroz.nodo.core.inference.EngineConfig
import com.santiquiroz.nodo.core.inference.EngineState
import com.santiquiroz.nodo.core.inference.GenerationEvent
import com.santiquiroz.nodo.core.inference.GenerationParams
import com.santiquiroz.nodo.core.inference.GenerationStats
import com.santiquiroz.nodo.core.inference.InferenceEngine
import com.santiquiroz.nodo.core.inference.ModelInfo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow

class FakeInferenceEngine : InferenceEngine {
    override val state = MutableStateFlow<EngineState>(EngineState.Idle)
    var respuesta: List<String> = listOf("Hola", " mundo")
    var ultimosMensajes: List<ChatMessage> = emptyList()

    override suspend fun load(modelPath: String, config: EngineConfig) {
        state.value = EngineState.Ready(ModelInfo(modelPath.substringAfterLast('/'), modelPath, 1000L))
    }

    override suspend fun unload() {
        state.value = EngineState.Idle
    }

    override fun generate(messages: List<ChatMessage>, params: GenerationParams): Flow<GenerationEvent> = flow {
        ultimosMensajes = messages
        respuesta.forEach { emit(GenerationEvent.Token(it)) }
        emit(GenerationEvent.Done(GenerationStats(10, respuesta.size, 50, 200)))
    }
}
```

```kotlin
// src/test/java/com/santiquiroz/nodo/feature/chat/ChatViewModelTest.kt
package com.santiquiroz.nodo.feature.chat

import app.cash.turbine.test
import com.santiquiroz.nodo.core.inference.ChatMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var engine: FakeInferenceEngine
    private lateinit var repo: ModelFilesRepository

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        engine = FakeInferenceEngine()
        repo = object : ModelFilesRepository {
            override fun listar() = listOf(ModelFile("tiny.gguf", "/models/tiny.gguf", 500L))
        }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun crearVm() = ChatViewModel(engine, repo)

    @Test
    fun `al iniciar lista los modelos disponibles`() = runTest {
        val vm = crearVm()
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(listOf("tiny.gguf"), vm.uiState.value.availableModels.map { it.name })
    }

    @Test
    fun `seleccionar modelo lo carga en el motor`() = runTest {
        val vm = crearVm()
        vm.onSelectModel(ModelFile("tiny.gguf", "/models/tiny.gguf", 500L))
        dispatcher.scheduler.advanceUntilIdle()
        assertTrue(vm.uiState.value.engineState is com.santiquiroz.nodo.core.inference.EngineState.Ready)
    }

    @Test
    fun `enviar agrega mensaje del usuario y acumula tokens del asistente`() = runTest {
        val vm = crearVm()
        vm.onSelectModel(ModelFile("tiny.gguf", "/models/tiny.gguf", 500L))
        dispatcher.scheduler.advanceUntilIdle()
        vm.onInputChange("hola")
        vm.onSend()
        dispatcher.scheduler.advanceUntilIdle()

        val mensajes = vm.uiState.value.messages
        assertEquals(2, mensajes.size)
        assertEquals(ChatMessage.Role.USER, mensajes[0].role)
        assertEquals("hola", mensajes[0].content)
        assertEquals(ChatMessage.Role.ASSISTANT, mensajes[1].role)
        assertEquals("Hola mundo", mensajes[1].content)
        assertFalse(vm.uiState.value.isGenerating)
        assertNotNull(vm.uiState.value.lastStats)
        assertEquals("", vm.uiState.value.input)
    }

    @Test
    fun `el historial completo viaja al motor en cada turno`() = runTest {
        val vm = crearVm()
        vm.onSelectModel(ModelFile("tiny.gguf", "/models/tiny.gguf", 500L))
        dispatcher.scheduler.advanceUntilIdle()
        vm.onInputChange("primera")
        vm.onSend()
        dispatcher.scheduler.advanceUntilIdle()
        vm.onInputChange("segunda")
        vm.onSend()
        dispatcher.scheduler.advanceUntilIdle()

        // user(primera) + assistant + user(segunda)
        assertEquals(3, engine.ultimosMensajes.size)
    }

    @Test
    fun `no envia si esta generando o input vacio`() = runTest {
        val vm = crearVm()
        vm.onSelectModel(ModelFile("tiny.gguf", "/models/tiny.gguf", 500L))
        dispatcher.scheduler.advanceUntilIdle()
        vm.onSend()   // input vacío
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(0, vm.uiState.value.messages.size)
    }

    @Test
    fun `isGenerating es true durante el stream`() = runTest {
        val vm = crearVm()
        vm.onSelectModel(ModelFile("tiny.gguf", "/models/tiny.gguf", 500L))
        dispatcher.scheduler.advanceUntilIdle()
        vm.uiState.test {
            vm.onInputChange("hola")
            vm.onSend()
            // Se ve al menos un estado intermedio con isGenerating=true antes del final
            var vioGenerando = false
            while (true) {
                val estado = awaitItem()
                if (estado.isGenerating) vioGenerando = true
                if (!estado.isGenerating && estado.messages.size == 2) break
            }
            assertTrue(vioGenerando)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
```

- [ ] **Step 4: verificar RED**

```bash
./gradlew :feature:chat:testDebugUnitTest
```

Expected: FALLA compilación (`ChatViewModel` no existe). Es el RED esperado.

- [ ] **Step 5 (GREEN): `ChatViewModel.kt`**

```kotlin
package com.santiquiroz.nodo.feature.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.santiquiroz.nodo.core.inference.ChatMessage
import com.santiquiroz.nodo.core.inference.EngineState
import com.santiquiroz.nodo.core.inference.GenerationEvent
import com.santiquiroz.nodo.core.inference.GenerationStats
import com.santiquiroz.nodo.core.inference.InferenceEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ChatUiState(
    val availableModels: List<ModelFile> = emptyList(),
    val engineState: EngineState = EngineState.Idle,
    val messages: List<ChatMessage> = emptyList(),
    val input: String = "",
    val isGenerating: Boolean = false,
    val lastStats: GenerationStats? = null,
    val error: String? = null,
)

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val engine: InferenceEngine,
    private val modelFiles: ModelFilesRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState = _uiState.asStateFlow()

    init {
        onRefreshModels()
        viewModelScope.launch {
            engine.state.collect { estado ->
                _uiState.update { it.copy(engineState = estado) }
            }
        }
    }

    fun onRefreshModels() {
        _uiState.update { it.copy(availableModels = modelFiles.listar()) }
    }

    fun onInputChange(texto: String) {
        _uiState.update { it.copy(input = texto) }
    }

    fun onSelectModel(modelo: ModelFile) {
        viewModelScope.launch {
            _uiState.update { it.copy(messages = emptyList(), lastStats = null, error = null) }
            engine.load(modelo.path)
        }
    }

    fun onSend() {
        val texto = _uiState.value.input.trim()
        if (texto.isEmpty() || _uiState.value.isGenerating) return
        if (_uiState.value.engineState !is EngineState.Ready) return

        val historial = _uiState.value.messages + ChatMessage(ChatMessage.Role.USER, texto)
        _uiState.update { it.copy(messages = historial, input = "", isGenerating = true, error = null) }

        viewModelScope.launch {
            val acumulado = StringBuilder()
            engine.generate(historial).collect { evento ->
                when (evento) {
                    is GenerationEvent.Token -> {
                        acumulado.append(evento.text)
                        _uiState.update {
                            it.copy(messages = historial + ChatMessage(ChatMessage.Role.ASSISTANT, acumulado.toString()))
                        }
                    }
                    is GenerationEvent.Done -> _uiState.update {
                        it.copy(isGenerating = false, lastStats = evento.stats)
                    }
                    is GenerationEvent.Failure -> _uiState.update {
                        it.copy(isGenerating = false, error = evento.message)
                    }
                }
            }
        }
    }
}
```

- [ ] **Step 6: verificar GREEN**

```bash
./gradlew :feature:chat:testDebugUnitTest
```

Expected: PASS (6 tests).

- [ ] **Step 7: Commit**

```bash
git add -A && git commit -m "feat: ChatViewModel con TDD — estado de chat, streaming acumulado, selección de modelo y single-flight de UI
Pruebas:
- 6 tests de ViewModel con FakeInferenceEngine y Turbine"
```

---

### Task 9: Pantalla de Chat + wiring en `:app`

**Files:**
- Create: `feature/chat/src/main/java/.../ChatScreen.kt`
- Modify: `app/build.gradle.kts` (deps a `:feature:chat`, `:core:inference`), `NodoNavHost.kt` (ruta chat real)

**Interfaces:**
- Consumes: `ChatViewModel`, `ChatUiState` (Task 8).
- Produces: `@Composable fun ChatScreen(viewModel: ChatViewModel = hiltViewModel())`.

- [ ] **Step 1: `ChatScreen.kt`**

```kotlin
package com.santiquiroz.nodo.feature.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.santiquiroz.nodo.core.inference.ChatMessage
import com.santiquiroz.nodo.core.inference.EngineState

@Composable
fun ChatScreen(viewModel: ChatViewModel = hiltViewModel()) {
    val estado by viewModel.uiState.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()

    LaunchedEffect(estado.messages.size, (estado.messages.lastOrNull()?.content?.length ?: 0)) {
        if (estado.messages.isNotEmpty()) listState.animateScrollToItem(estado.messages.lastIndex)
    }

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        SelectorModelo(estado, viewModel::onSelectModel, viewModel::onRefreshModels)

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(estado.messages) { mensaje -> BurbujaMensaje(mensaje) }
        }

        estado.lastStats?.let { stats ->
            Text(
                text = "%.1f tok/s · %d tokens · primer token %d ms".format(
                    stats.tokensPerSecond, stats.generatedTokens, stats.timeToFirstTokenMs,
                ),
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 4.dp),
            )
        }
        estado.error?.let { error ->
            Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = estado.input,
                onValueChange = viewModel::onInputChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text("Escribe un mensaje…") },
                enabled = estado.engineState is EngineState.Ready,
            )
            if (estado.isGenerating) {
                CircularProgressIndicator(Modifier.padding(start = 12.dp).widthIn(max = 28.dp))
            } else {
                IconButton(onClick = viewModel::onSend, enabled = estado.engineState is EngineState.Ready) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Enviar")
                }
            }
        }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun SelectorModelo(
    estado: ChatUiState,
    onSeleccionar: (ModelFile) -> Unit,
    onRefrescar: () -> Unit,
) {
    var expandido by remember { mutableStateOf(false) }
    val etiqueta = when (val e = estado.engineState) {
        is EngineState.Ready -> e.model.name
        is EngineState.Loading -> "Cargando ${e.modelName}…"
        is EngineState.Error -> "Error: ${e.message}"
        EngineState.Idle -> if (estado.availableModels.isEmpty())
            "Sin modelos en Android/data/com.santiquiroz.nodo/files/models" else "Elige un modelo"
    }
    ExposedDropdownMenuBox(
        expanded = expandido,
        onExpandedChange = { expandido = it; if (it) onRefrescar() },
    ) {
        OutlinedTextField(
            value = etiqueta,
            onValueChange = {},
            readOnly = true,
            modifier = Modifier.fillMaxWidth().menuAnchor(),
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expandido) },
            label = { Text("Modelo") },
        )
        ExposedDropdownMenu(expanded = expandido, onDismissRequest = { expandido = false }) {
            estado.availableModels.forEach { modelo ->
                DropdownMenuItem(
                    text = { Text("${modelo.name} · ${modelo.sizeBytes / 1_000_000} MB") },
                    onClick = {
                        expandido = false
                        onSeleccionar(modelo)
                    },
                )
            }
        }
    }
}

@Composable
private fun BurbujaMensaje(mensaje: ChatMessage) {
    val esUsuario = mensaje.role == ChatMessage.Role.USER
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (esUsuario) Arrangement.End else Arrangement.Start,
    ) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = if (esUsuario) MaterialTheme.colorScheme.surfaceVariant
                else MaterialTheme.colorScheme.surface,
            ),
        ) {
            Text(
                mensaje.content,
                Modifier.padding(10.dp).widthIn(max = 300.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}
```

- [ ] **Step 2: wiring `:app`** — en `app/build.gradle.kts` añadir:

```kotlin
dependencies {
    implementation(project(":core:inference"))
    implementation(project(":feature:chat"))
    // ...existentes...
}
```

En `NodoNavHost.kt`, reemplazar el bucle genérico para dar ruta real a chat:

```kotlin
NavHost(
    navController = navController,
    startDestination = "modelos",
    modifier = Modifier.padding(padding),
) {
    composable("modelos") { PlaceholderScreen("Modelos") }
    composable("explorar") { PlaceholderScreen("Explorar") }
    composable("servidor") { PlaceholderScreen("Servidor") }
    composable("chat") { ChatScreen() }
    composable("ajustes") { PlaceholderScreen("Ajustes") }
}
```

(import `com.santiquiroz.nodo.feature.chat.ChatScreen`)

- [ ] **Step 3: build completo + tests**

```bash
./gradlew assembleDebug testDebugUnitTest
```

Expected: BUILD SUCCESSFUL, tests PASS.

- [ ] **Step 4: Commit**

```bash
git add -A && git commit -m "feat: pantalla de Chat — selector de modelo, burbujas con streaming, stats tok/s; wiring de nav en :app"
```

---

### Task 10: Gate físico en S25 Ultra — tok/s reales

**Files:**
- Create: `docs/BENCHMARKS.md`

- [ ] **Step 1: descargar GGUFs de prueba en el PC** (ungated, Apache-2.0):

```bash
cd /c/personal/nodo
mkdir -p .modelos-prueba
curl -L -o .modelos-prueba/qwen2.5-0.5b-q4km.gguf \
  "https://huggingface.co/bartowski/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/Qwen2.5-0.5B-Instruct-Q4_K_M.gguf"
curl -L -o .modelos-prueba/qwen2.5-1.5b-q4km.gguf \
  "https://huggingface.co/bartowski/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/Qwen2.5-1.5B-Instruct-Q4_K_M.gguf"
echo ".modelos-prueba/" >> .gitignore
```

- [ ] **Step 2: instalar app + push de modelos**

```bash
SERIAL=adb-R5CY82F28DM-0ahBFS._adb-tls-connect._tcp
adb -s $SERIAL install -r app/build/outputs/apk/debug/app-debug.apk
adb -s $SERIAL shell mkdir -p /sdcard/Android/data/com.santiquiroz.nodo/files/models
adb -s $SERIAL push .modelos-prueba/qwen2.5-0.5b-q4km.gguf /sdcard/Android/data/com.santiquiroz.nodo/files/models/
adb -s $SERIAL push .modelos-prueba/qwen2.5-1.5b-q4km.gguf /sdcard/Android/data/com.santiquiroz.nodo/files/models/
```

Si el push a `Android/data` falla (restricción scoped storage): fallback `adb push` a `/data/local/tmp/` + `adb -s $SERIAL shell run-as com.santiquiroz.nodo cp /data/local/tmp/<f> files/../models/` — la app es debuggable. Último recurso: cambiar `ModelFilesRepositoryImpl` a `context.filesDir` + run-as.

- [ ] **Step 3: smoke por UI**

```bash
adb -s $SERIAL shell am start -n com.santiquiroz.nodo/.MainActivity
```

Manual/por screencap+input de adb: tab Chat → seleccionar `qwen2.5-0.5b` → enviar "Hola, ¿quién eres?" → verificar respuesta con streaming y línea de stats. Revisar `adb logcat -s nodo_llama` por errores.

- [ ] **Step 4: medir y registrar** — 3 prompts por modelo (corto, medio ~200 tokens de respuesta, seguimiento con historial). Anotar tok/s, primer token, RAM (`adb shell dumpsys meminfo com.santiquiroz.nodo | head -30`). Crear `docs/BENCHMARKS.md`:

```markdown
# Benchmarks — S25 Ultra (Snapdragon 8 Elite, 12 GB)

Fecha: 2026-08-04 · Nodo 0.1.0 · llama.cpp <TAG> · CPU 6 hilos · ctx 4096

| Modelo | Cuant | Tamaño | tok/s gen | 1er token | RSS app |
|---|---|---|---|---|---|
| Qwen2.5-0.5B-Instruct | Q4_K_M | ~0.4 GB | _ | _ ms | _ MB |
| Qwen2.5-1.5B-Instruct | Q4_K_M | ~1.0 GB | _ | _ ms | _ MB |

Notas: throttling térmico observado, calidad subjetiva de respuestas, próximos candidatos (3B).
```

- [ ] **Step 5: Commit + push final**

```bash
git add -A && git commit -m "docs: benchmarks reales de Fase 1 en S25 Ultra — tok/s, latencia de primer token y RSS"
git push origin master
```

---

## Self-review (hecho al escribir)

- Cobertura del spec: Fase 0 (esqueleto multi-módulo, Compose, Hilt, 5 tabs, build verde, push) → Tasks 1-3. Fase 1 (`:core:inference` + `LlamaCppEngine`, GGUF local, streaming, Chat funcional, gate físico con tok/s) → Tasks 4-10. Módulos `:core:models`, `:core:capability`, `:core:serving` NO se crean aún (YAGNI — llegan en Fases 2-4).
- Tipos consistentes entre tasks (contrato de Task 4 usado literal en 6-9).
- Riesgo principal (drift API llama.cpp) documentado con instrucción de resolución en Tasks 5-6.
