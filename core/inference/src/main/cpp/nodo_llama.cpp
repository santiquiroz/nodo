#include <jni.h>
#include <string>
#include <vector>
#include <algorithm>
#include <cstring>
#include <android/log.h>
#include "llama.h"

#define LOG_TAG "nodo_llama"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// Debe coincidir con NodoStatus.kt
enum NodoStatus {
    NODO_GENERANDO      = 0,
    NODO_FIN_NATURAL    = 1,
    NODO_ERROR_SESION   = 2,
    NODO_ERROR_DECODE   = 3,
    NODO_ERROR_CONTEXTO = 4,
    NODO_ERROR_PROMPT   = 5,
};

struct NodoSession {
    llama_model* model = nullptr;
    llama_context* ctx = nullptr;
    llama_sampler* smpl = nullptr;
    const llama_vocab* vocab = nullptr;
    std::string pending;   // bytes UTF-8 incompletos entre tokens
    bool generating = false;
    int status = NODO_GENERANDO;
    int n_past = 0;
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

static void construir_sampler(NodoSession* s, float temp, float minP) {
    if (s->smpl) llama_sampler_free(s->smpl);
    s->smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(s->smpl, llama_sampler_init_min_p(minP, 1));
    llama_sampler_chain_add(s->smpl, llama_sampler_init_temp(temp));
    llama_sampler_chain_add(s->smpl, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_santiquiroz_nodo_core_inference_LlamaNative_systemInfo(JNIEnv* env, jobject) {
    return env->NewStringUTF(llama_print_system_info());
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_santiquiroz_nodo_core_inference_LlamaNative_load(
        JNIEnv* env, jobject, jstring jpath, jint nCtx, jint nThreads) {
    llama_backend_init();
    const char* path = env->GetStringUTFChars(jpath, nullptr);
    if (!path) { LOGE("load: GetStringUTFChars devolvió NULL"); return 0; }
    llama_model_params mp = llama_model_default_params();
    mp.load_mode = LLAMA_LOAD_MODE_MMAP;   // obligatorio: sin mmap un 3B Q4 revienta el heap
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
    construir_sampler(s, 0.8f, 0.05f);
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

extern "C" JNIEXPORT jint JNICALL
Java_com_santiquiroz_nodo_core_inference_LlamaNative_lastStatus(JNIEnv*, jobject, jlong handle) {
    auto* s = reinterpret_cast<NodoSession*>(handle);
    return s ? s->status : NODO_ERROR_SESION;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_santiquiroz_nodo_core_inference_LlamaNative_contextLength(JNIEnv*, jobject, jlong handle) {
    auto* s = reinterpret_cast<NodoSession*>(handle);
    return s ? (jint) llama_n_ctx(s->ctx) : 0;
}

extern "C" JNIEXPORT void JNICALL
Java_com_santiquiroz_nodo_core_inference_LlamaNative_setSampling(
        JNIEnv*, jobject, jlong handle, jfloat temp, jfloat minP) {
    auto* s = reinterpret_cast<NodoSession*>(handle);
    if (!s) return;
    construir_sampler(s, temp, minP);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_santiquiroz_nodo_core_inference_LlamaNative_formatChat(
        JNIEnv* env, jobject, jlong handle, jobjectArray jroles, jobjectArray jtexts) {
    auto* s = reinterpret_cast<NodoSession*>(handle);
    if (!s) return nullptr;
    const char* tmpl = llama_model_chat_template(s->model, nullptr);
    if (!tmpl) return nullptr;   // sin plantilla: Kotlin usa fallback ChatML

    jsize n = env->GetArrayLength(jroles);
    if (n <= 0 || env->GetArrayLength(jtexts) != n) return nullptr;

    std::vector<std::string> roles(n), texts(n);
    std::vector<llama_chat_message> msgs(n);
    for (jsize i = 0; i < n; i++) {
        auto jr = (jstring) env->GetObjectArrayElement(jroles, i);
        auto jt = (jstring) env->GetObjectArrayElement(jtexts, i);
        if (env->ExceptionCheck()) { env->ExceptionClear(); return nullptr; }
        if (!jr || !jt) return nullptr;
        const char* r = env->GetStringUTFChars(jr, nullptr);
        const char* t = r ? env->GetStringUTFChars(jt, nullptr) : nullptr;
        if (!r || !t) {   // OutOfMemoryError pendiente: Kotlin cae al fallback
            if (r) env->ReleaseStringUTFChars(jr, r);
            env->ExceptionClear();
            return nullptr;
        }
        roles[i] = r; texts[i] = t;
        env->ReleaseStringUTFChars(jr, r);
        env->ReleaseStringUTFChars(jt, t);
        env->DeleteLocalRef(jr);
        env->DeleteLocalRef(jt);
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
    s->n_past = 0;
    s->status = NODO_ERROR_PROMPT;

    // Conversación completa re-decodificada cada turno (v1): limpiar KV
    llama_memory_clear(llama_get_memory(s->ctx), true);

    const char* prompt = env->GetStringUTFChars(jprompt, nullptr);
    if (!prompt) { env->ExceptionClear(); return -1; }
    int text_len = (int) strlen(prompt);
    int n_tokens = -llama_tokenize(s->vocab, prompt, text_len, nullptr, 0, true, true);
    if (n_tokens <= 0) { env->ReleaseStringUTFChars(jprompt, prompt); return -1; }
    std::vector<llama_token> tokens(n_tokens);
    llama_tokenize(s->vocab, prompt, text_len, tokens.data(), n_tokens, true, true);
    env->ReleaseStringUTFChars(jprompt, prompt);

    int n_ctx = (int) llama_n_ctx(s->ctx);
    if (n_tokens >= n_ctx) {
        s->status = NODO_ERROR_CONTEXTO;
        LOGE("start: prompt de %d tokens no cabe en contexto de %d", n_tokens, n_ctx);
        return -1;
    }

    int n_batch = 512;
    for (int i = 0; i < n_tokens; i += n_batch) {
        int chunk = std::min(n_batch, n_tokens - i);
        llama_batch batch = llama_batch_get_one(tokens.data() + i, chunk);
        if (llama_decode(s->ctx, batch) != 0) {
            s->status = NODO_ERROR_DECODE;
            LOGE("start: llama_decode fallo en prompt");
            return -1;
        }
    }
    s->n_past = n_tokens;
    s->generating = true;
    s->status = NODO_GENERANDO;
    return n_tokens;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_santiquiroz_nodo_core_inference_LlamaNative_next(JNIEnv* env, jobject, jlong handle) {
    auto* s = reinterpret_cast<NodoSession*>(handle);
    if (!s) return nullptr;
    if (!s->generating) {
        if (s->status == NODO_GENERANDO) s->status = NODO_ERROR_SESION;
        return nullptr;
    }

    if (s->n_past >= (int) llama_n_ctx(s->ctx)) {
        s->generating = false;
        s->status = NODO_ERROR_CONTEXTO;
        return nullptr;
    }

    llama_token tok = llama_sampler_sample(s->smpl, s->ctx, -1);
    if (llama_vocab_is_eog(s->vocab, tok)) {
        s->generating = false;
        s->status = NODO_FIN_NATURAL;
        return nullptr;
    }
    char buf[256];
    int n = llama_token_to_piece(s->vocab, tok, buf, sizeof(buf), 0, true);
    if (n > 0) s->pending.append(buf, n);

    llama_batch batch = llama_batch_get_one(&tok, 1);
    if (llama_decode(s->ctx, batch) != 0) {
        s->generating = false;
        s->status = NODO_ERROR_DECODE;
        LOGE("next: llama_decode fallo");
        return nullptr;
    }
    s->n_past++;
    return env->NewStringUTF(extraer_utf8_valido(s->pending).c_str());
}
