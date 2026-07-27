#pragma once

// Windows ships winsqlite3.dll and its import library, but some Windows SDK
// installations omit winsqlite3.h. Keep the small API surface used by this
// project declared locally so the portable EXE does not need another DLL.

#include <cstdint>

extern "C" {
struct sqlite3;
struct sqlite3_stmt;
using sqlite3_int64 = int64_t;
using sqlite3_destructor_type = void (*)(void*);

__declspec(dllimport) int __cdecl sqlite3_open16(const void* filename, sqlite3** database);
__declspec(dllimport) int __cdecl sqlite3_close(sqlite3* database);
__declspec(dllimport) const char* __cdecl sqlite3_errmsg(sqlite3* database);
__declspec(dllimport) int __cdecl sqlite3_busy_timeout(sqlite3* database, int milliseconds);
__declspec(dllimport) int __cdecl sqlite3_exec(sqlite3* database, const char* sql,
    int (__cdecl* callback)(void*, int, char**, char**), void* context, char** errorMessage);
__declspec(dllimport) void __cdecl sqlite3_free(void* value);
__declspec(dllimport) int __cdecl sqlite3_prepare_v2(sqlite3* database, const char* sql,
    int bytes, sqlite3_stmt** statement, const char** tail);
__declspec(dllimport) int __cdecl sqlite3_finalize(sqlite3_stmt* statement);
__declspec(dllimport) int __cdecl sqlite3_bind_text16(sqlite3_stmt* statement, int index,
    const void* value, int bytes, sqlite3_destructor_type destructor);
__declspec(dllimport) int __cdecl sqlite3_bind_int64(sqlite3_stmt* statement, int index, sqlite3_int64 value);
__declspec(dllimport) int __cdecl sqlite3_step(sqlite3_stmt* statement);
__declspec(dllimport) int __cdecl sqlite3_reset(sqlite3_stmt* statement);
__declspec(dllimport) int __cdecl sqlite3_clear_bindings(sqlite3_stmt* statement);
__declspec(dllimport) sqlite3* __cdecl sqlite3_db_handle(sqlite3_stmt* statement);
__declspec(dllimport) const void* __cdecl sqlite3_column_text16(sqlite3_stmt* statement, int column);
}

constexpr int SQLITE_OK = 0;
constexpr int SQLITE_ROW = 100;
constexpr int SQLITE_DONE = 101;
#define SQLITE_TRANSIENT reinterpret_cast<sqlite3_destructor_type>(-1)
