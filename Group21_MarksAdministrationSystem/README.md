# Group 21 – Marks Administration System

A Java (JDBC + MySQL) console application for the SDN260S group project.
Lecturers manage marks for **their own modules only**; the **HoD** can access **all modules**.
The system supports weighted assessments, CSV import/export, automatic missing-entry
flagging, audit logging, and multithreaded file I/O.

## 1. Requirements
- JDK 17+
- MySQL Server 8+
- MySQL Connector/J (JDBC driver) — place `mysql-connector-j-8.x.x.jar` in the `lib/` folder.

## 2. Database setup
```sql
mysql -u root -p < schema.sql
```
This creates the `marks_admin` database and a least-privilege user `marksuser`.

## 3. Compile & run
```bash
# from the project root
javac -cp "lib/mysql-connector-j-8.4.0.jar" -d out $(find src -name "*.java")
java  -cp "out;lib/mysql-connector-j-8.4.0.jar" Main        # Windows
java  -cp "out:lib/mysql-connector-j-8.4.0.jar" Main        # Linux/macOS
```
*(Adjust the jar name to whichever Connector/J version you downloaded.)*

## 4. Default accounts (auto-seeded on first run)
| Role     | Username | Password  |
|----------|----------|-----------|
| HoD      | `admin`  | `admin123`|
| Lecturer | `lect`   | `lect123` |

> Passwords are stored as salted SHA-256 hashes — never as plain text.

## 5. CSV format
Header row: `StudentNumber,StudentName,<Assessment1>,<Assessment2>,...`
```
StudentNumber,StudentName,Test 1,Test 2,Practical Exam
CPUT24001,Thandi Nkosi,45,52,
CPUT24002,John Mokoena,MISSING,61,70
```
- A blank cell or the word `MISSING` is imported as *no mark* and is **automatically flagged**.
- A student number that does not exist is **created and enrolled automatically**.
- Assessment columns that do not exist in the module are skipped and reported as warnings.

## 6. Feature map (rubric alignment)
| Requirement | Where |
|---|---|
| OOP / modularity | `model/`, `service/`, `db/`, `util/` packages |
| File handling / streams | `service/CsvService` (BufferedReader/Writer) |
| Multithreading | CSV import/export run on a worker thread pool (`ExecutorService`) |
| Collections / generics | `List<Module>`, `Map<Assessment, Double>`, `HashMap` lookups |
| Algorithms & Big-O | Marksheet aggregation O(A + M) with HashMaps - see code comments |
| Data validation & error handling | Input validation, weight-sum rule (<= 100%), transactions on import |
| Logging / reporting | `audit_log` table, missing-entry report, HoD summary report |
| Security | Salted password hashing, prepared statements (SQL-injection safe), role checks |

## 7. Known limitations (for the report)
- The CSV parser does not support quoted fields containing commas.
- Weighted totals assume assessment weights sum to 100%; the system enforces this when adding assessments.
