#include <jni.h>

#include <cstdio>
#include <cstring>
#include <mutex>
#include <string>
#include <string_view>
#include <utility>
#include <vector>

#include "lexdb.h"

namespace {
constexpr char kRulexDbMagic[] = "RUTTS_RULEX_DB_V1";
constexpr size_t kRulexDbMagicSize = sizeof(kRulexDbMagic) - 1;

struct DbRecord {
    std::string key;
    std::string value;
};

struct DbContents {
    std::vector<DbRecord> explicitDict;
    std::vector<DbRecord> implicitDict;
    std::vector<DbRecord> lexiconRules;
    std::vector<DbRecord> generalRules;
    std::vector<DbRecord> prefixRules;
    std::vector<DbRecord> correctionRules;
};

class DbReader {
public:
    explicit DbReader(std::string_view data) : data_(data) {}

    bool ReadU32(uint32_t* value) {
        if (!value || data_.size() - offset_ < 4) {
            return false;
        }
        const auto b0 = static_cast<uint8_t>(data_[offset_]);
        const auto b1 = static_cast<uint8_t>(data_[offset_ + 1]);
        const auto b2 = static_cast<uint8_t>(data_[offset_ + 2]);
        const auto b3 = static_cast<uint8_t>(data_[offset_ + 3]);
        offset_ += 4;
        *value = static_cast<uint32_t>(b0) |
            (static_cast<uint32_t>(b1) << 8) |
            (static_cast<uint32_t>(b2) << 16) |
            (static_cast<uint32_t>(b3) << 24);
        return true;
    }

    bool ReadChunk(std::string* value) {
        uint32_t length = 0;
        if (!value || !ReadU32(&length) || data_.size() - offset_ < length) {
            return false;
        }
        *value = std::string(data_.substr(offset_, length));
        offset_ += length;
        return true;
    }

    bool Finished() const {
        return offset_ == data_.size();
    }

private:
    std::string_view data_;
    size_t offset_ = 0;
};

bool ParseRulexSeed(const std::string& contents, DbContents* output) {
    if (!output || contents.size() < kRulexDbMagicSize ||
        std::memcmp(contents.data(), kRulexDbMagic, kRulexDbMagicSize) != 0) {
        return false;
    }

    DbReader reader(std::string_view(contents).substr(kRulexDbMagicSize));
    uint32_t sectionCount = 0;
    if (!reader.ReadU32(&sectionCount)) {
        return false;
    }

    DbContents parsed;
    for (uint32_t sectionIndex = 0; sectionIndex < sectionCount; ++sectionIndex) {
        std::string sectionName;
        uint32_t recordCount = 0;
        if (!reader.ReadChunk(&sectionName) || !reader.ReadU32(&recordCount)) {
            return false;
        }

        std::vector<DbRecord> records;
        records.reserve(static_cast<size_t>(recordCount));
        for (uint32_t recordIndex = 0; recordIndex < recordCount; ++recordIndex) {
            DbRecord record;
            if (!reader.ReadChunk(&record.key) || !reader.ReadChunk(&record.value)) {
                return false;
            }
            records.push_back(std::move(record));
        }

        if (sectionName == "explicit") {
            parsed.explicitDict = std::move(records);
        } else if (sectionName == "implicit") {
            parsed.implicitDict = std::move(records);
        } else if (sectionName == "lexicon") {
            parsed.lexiconRules = std::move(records);
        } else if (sectionName == "general") {
            parsed.generalRules = std::move(records);
        } else if (sectionName == "prefix") {
            parsed.prefixRules = std::move(records);
        } else if (sectionName == "correction") {
            parsed.correctionRules = std::move(records);
        }
    }

    if (!reader.Finished()) {
        return false;
    }
    *output = std::move(parsed);
    return true;
}

bool StoreDictionary(RULEXDB* database, const std::vector<DbRecord>& records, int type) {
    for (const DbRecord& record : records) {
        const int result = rulexdb_subscribe_item(
            database,
            record.key.c_str(),
            record.value.c_str(),
            type,
            0);
        if (result != RULEXDB_SUCCESS && result != RULEXDB_SPECIAL) {
            return false;
        }
    }
    return true;
}

bool StoreRules(RULEXDB* database, const std::vector<DbRecord>& records, int type) {
    for (const DbRecord& record : records) {
        std::string rule = record.key;
        if (!record.value.empty()) {
            rule.push_back(' ');
            rule += record.value;
        }
        if (rulexdb_subscribe_rule(database, rule.c_str(), type, 0) != RULEXDB_SUCCESS) {
            return false;
        }
    }
    return true;
}

bool BuildReferenceDatabase(const std::string& path, const std::string& seed) {
    DbContents contents;
    if (!ParseRulexSeed(seed, &contents)) {
        return false;
    }

    std::remove(path.c_str());
    std::remove((path + "-lock").c_str());
    RULEXDB* database = rulexdb_open(path.c_str(), RULEXDB_CREATE);
    if (!database) {
        return false;
    }

    const bool stored =
        StoreDictionary(database, contents.explicitDict, RULEXDB_EXCEPTION) &&
        StoreDictionary(database, contents.implicitDict, RULEXDB_LEXBASE) &&
        StoreRules(database, contents.lexiconRules, RULEXDB_LEXCLASS) &&
        StoreRules(database, contents.generalRules, RULEXDB_RULE) &&
        StoreRules(database, contents.prefixRules, RULEXDB_PREFIX) &&
        StoreRules(database, contents.correctionRules, RULEXDB_CORRECTOR);
    rulexdb_close(database);

    if (!stored) {
        std::remove(path.c_str());
        std::remove((path + "-lock").c_str());
    }
    return stored;
}

std::string ReadBytes(JNIEnv* env, jbyteArray byteArray) {
    if (!env || !byteArray) {
        return {};
    }
    const jsize length = env->GetArrayLength(byteArray);
    std::string bytes(static_cast<size_t>(length), '\0');
    if (length > 0) {
        env->GetByteArrayRegion(byteArray, 0, length, reinterpret_cast<jbyte*>(bytes.data()));
    }
    return bytes;
}

std::string ReadString(JNIEnv* env, jstring value) {
    if (!env || !value) {
        return {};
    }
    const char* utf8 = env->GetStringUTFChars(value, nullptr);
    if (!utf8) {
        return {};
    }
    std::string result(utf8);
    env->ReleaseStringUTFChars(value, utf8);
    return result;
}

jbyteArray WriteBytes(JNIEnv* env, const std::string& data) {
    if (!env) {
        return nullptr;
    }
    jbyteArray result = env->NewByteArray(static_cast<jsize>(data.size()));
    if (!result || data.empty()) {
        return result;
    }
    env->SetByteArrayRegion(
        result,
        0,
        static_cast<jsize>(data.size()),
        reinterpret_cast<const jbyte*>(data.data()));
    return result;
}

class RulexRuntime {
public:
    ~RulexRuntime() {
        std::lock_guard<std::mutex> lock(mutex_);
        Close();
    }

    bool EnsureLoaded(const std::string& path, const std::string& seed) {
        std::lock_guard<std::mutex> lock(mutex_);
        if (database_) {
            return true;
        }

        database_ = rulexdb_open(path.c_str(), RULEXDB_SEARCH);
        if (database_) {
            return true;
        }
        if (seed.empty() || !BuildReferenceDatabase(path, seed)) {
            return false;
        }

        database_ = rulexdb_open(path.c_str(), RULEXDB_SEARCH);
        return database_ != nullptr;
    }

    bool IsLoaded() const {
        std::lock_guard<std::mutex> lock(mutex_);
        return database_ != nullptr;
    }

    std::string SearchWord(const std::string& key) const {
        std::lock_guard<std::mutex> lock(mutex_);
        if (!database_ || key.empty() || key.size() > RULEXDB_MAX_KEY_SIZE) {
            return key;
        }

        char result[RULEXDB_BUFSIZE]{};
        return rulexdb_search(database_, key.c_str(), result, 0) == RULEXDB_SUCCESS
            ? std::string(result)
            : key;
    }

private:
    void Close() {
        if (database_) {
            rulexdb_close(database_);
            database_ = nullptr;
        }
    }

    mutable std::mutex mutex_;
    mutable RULEXDB* database_ = nullptr;
};

RulexRuntime g_rulexRuntime;
}  // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_ru_hlebushek_RuTTS_engine_NativeRulex_nativeEnsureLoadedRaw(
    JNIEnv* env,
    jclass,
    jstring path,
    jbyteArray seed) {
    if (!env) {
        return JNI_FALSE;
    }
    return g_rulexRuntime.EnsureLoaded(ReadString(env, path), ReadBytes(env, seed))
        ? JNI_TRUE
        : JNI_FALSE;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_ru_hlebushek_RuTTS_engine_NativeRulex_nativeSearchWordRaw(
    JNIEnv* env,
    jclass,
    jbyteArray word) {
    return env
        ? WriteBytes(env, g_rulexRuntime.SearchWord(ReadBytes(env, word)))
        : nullptr;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_ru_hlebushek_RuTTS_engine_NativeRulex_nativeIsLoadedRaw(
    JNIEnv*,
    jclass) {
    return g_rulexRuntime.IsLoaded() ? JNI_TRUE : JNI_FALSE;
}

