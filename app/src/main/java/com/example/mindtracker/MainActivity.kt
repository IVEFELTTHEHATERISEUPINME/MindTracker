package com.example.mindtracker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import androidx.work.*

import java.util.concurrent.TimeUnit

/**
 * Главный экран приложения "Трекер когнитивных функций"
 *
 * Функционал:
 * - Тест памяти Digit Span (запоминание последовательности цифр)
 * - Сохранение рекорда и последнего результата через SharedPreferences
 * - Ежедневные напоминания через WorkManager
 * - Статистика и интерпретация результатов
 *
 * @author Валишин М.М., Хабиров Э.И.
 * @version 2.0
 */
class MainActivity : AppCompatActivity() {

    companion object {
        // Уникальное имя для WorkRequest (чтобы не создавать дубликаты)
        private const val WORK_NAME = "daily_reminder_work"
        // Фиксированный ID уведомления (чтобы не накапливались)
        private const val NOTIFICATION_ID = 1001
    }

    // ==================== ПЕРЕМЕННЫЕ СОСТОЯНИЯ ====================

    /** Текущий уровень, который показывается пользователю */
    private var currentLevel = 1

    /** Список цифр, которые нужно запомнить. Пример: [5, 2, 8] */
    private var currentSequence = mutableListOf<Int>()

    /** Результат ТЕКУЩЕЙ игры - максимальный успешно пройденный уровень */
    private var currentMaxScore = 0

    /** Рекорд за всё время (из SharedPreferences) */
    private var allTimeBest = 0

    // ==================== ЖИЗНЕННЫЙ ЦИКЛ ACTIVITY ====================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Запрос разрешения на уведомления для Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissions(
                arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
                1001
            )
        }

        // Загружаем сохранённые данные
        loadAllData()

        // Запускаем ежедневные напоминания
        setupReminders()

        // Настройка кнопок
        findViewById<Button>(R.id.btnMemoryTest).setOnClickListener {
            startMemoryTest()
        }

        findViewById<Button>(R.id.btnStatistics).setOnClickListener {
            showStatistics()
        }
    }

    // ==================== РАБОТА С ДАННЫМИ (SharedPreferences) ====================

    /**
     * Сохраняет рекорд за всё время.
     */
    private fun saveBestScore(score: Int) {
        val prefs = getSharedPreferences("mindtracker_prefs", Context.MODE_PRIVATE)
        prefs.edit().putInt("best_score", score).apply()
        allTimeBest = score
    }

    /**
     * Сохраняет результат последней игры.
     */
    private fun saveLastScore(score: Int) {
        val prefs = getSharedPreferences("mindtracker_prefs", Context.MODE_PRIVATE)
        prefs.edit().putInt("last_score", score).apply()
    }

    /**
     * Загружает все сохранённые данные.
     */
    private fun loadAllData() {
        val prefs = getSharedPreferences("mindtracker_prefs", Context.MODE_PRIVATE)
        allTimeBest = prefs.getInt("best_score", 0)
        currentMaxScore = prefs.getInt("last_score", 0)
    }

    // ==================== УВЕДОМЛЕНИЯ (WorkManager) ====================

    /**
     * Настраивает ежедневные напоминания.
     * Используется уникальное имя, чтобы не создавать дубликаты.
     */
    private fun setupReminders() {
        // Отменяем старые запросы
        WorkManager.getInstance(this).cancelUniqueWork(WORK_NAME)

        // Создаём периодическую задачу (каждый день в 10:00)
        val workRequest = PeriodicWorkRequestBuilder<ReminderWorker>(
            1, TimeUnit.DAYS
        ).setInitialDelay(10, TimeUnit.HOURS)
            .build()

        // Запускаем с уникальным именем
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            workRequest
        )
    }

    /**
     * Внутренний класс для отправки уведомлений.
     */
    class ReminderWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

        override fun doWork(): Result {
            createNotificationChannel()
            sendNotification()
            return Result.success()
        }

        private fun createNotificationChannel() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    "memory_channel",
                    "Тренировка памяти",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Напоминания о тренировке памяти"
                    setShowBadge(false)
                }
                val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                manager.createNotificationChannel(channel)
            }
        }

        private fun sendNotification() {
            val notification = NotificationCompat.Builder(applicationContext, "memory_channel")
                .setSmallIcon(android.R.drawable.ic_menu_edit)
                .setContentTitle("Тренируйте память!")
                .setContentText("Пройдите тест за 2 минуты, чтобы улучшить когнитивные функции")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .build()

            val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(NOTIFICATION_ID, notification)
        }
    }

    // ==================== ЛОГИКА ТЕСТА ПАМЯТИ ====================

    /**
     * Запускает новый тест памяти.
     * Важно: обнуляем результат текущей игры, но НЕ трогаем рекорд.
     */
    private fun startMemoryTest() {
        currentLevel = 1
        currentMaxScore = 0          // Обнуляем результат ТЕКУЩЕЙ игры
        generateNewSequence()
        showMemoryDialog()
    }

    /**
     * Генерирует последовательность цифр для текущего уровня.
     * Длина = уровень + 2 (1 уровень → 3 цифры, 2 уровень → 4 цифры)
     */
    private fun generateNewSequence() {
        val length = currentLevel + 2
        currentSequence.clear()
        repeat(length) {
            currentSequence.add((1..9).random())
        }
    }

    /**
     * Показывает диалог с последовательностью цифр.
     * Через 3 секунды показывает поле для ввода.
     * При нажатии "Отмена" - завершаем игру.
     */
    private fun showMemoryDialog() {
        val numbersText = currentSequence.joinToString("  ")

        AlertDialog.Builder(this)
            .setTitle("Тест памяти - Уровень $currentLevel")
            .setMessage("Запомните последовательность:\n\n$numbersText\n\nЧерез 3 секунды нужно будет ввести цифры")
            .setPositiveButton("Готово") { _, _ ->
                Handler(Looper.getMainLooper()).postDelayed({
                    showInputDialog()
                }, 3000)
            }
            .setNegativeButton("Отмена") { _, _ ->
                // Отмена - завершаем игру с текущим результатом
                saveAndShowResult()
            }
            .setCancelable(false)
            .show()
    }

    /**
     * Показывает диалог с полем для ввода ответа.
     */
    private fun showInputDialog() {
        val input = EditText(this).apply {
            hint = "Введите цифры подряд (например: 528)"
            inputType = InputType.TYPE_CLASS_NUMBER
        }

        AlertDialog.Builder(this)
            .setTitle("Введите последовательность")
            .setMessage("Введите все цифры подряд без пробелов:")
            .setView(input)
            .setPositiveButton("Проверить") { _, _ ->
                val userInput = input.text.toString().trim()
                checkAnswer(userInput)
            }
            .setNegativeButton("Сдаюсь") { _, _ ->
                saveAndShowResult()
            }
            .setCancelable(false)
            .show()
    }

    /**
     * Проверяет ответ пользователя.
     */
    private fun checkAnswer(userInput: String) {
        // Преобразуем "528" в [5, 2, 8]
        val userNumbers = userInput.map { it.toString().toIntOrNull() }
            .filterNotNull()

        if (userNumbers == currentSequence) {
            // ===== ПРАВИЛЬНЫЙ ОТВЕТ =====
            // Запоминаем, какой уровень только что прошли
            val completedLevel = currentLevel
            if (completedLevel > currentMaxScore) {
                currentMaxScore = completedLevel
            }

            // Переходим на следующий уровень
            currentLevel++

            AlertDialog.Builder(this)
                .setTitle("Правильно!")
                .setMessage("Уровень $completedLevel пройден!\n\nТекущий результат: $currentMaxScore")
                .setPositiveButton("Далее") { _, _ ->
                    generateNewSequence()
                    showMemoryDialog()
                }
                .setNegativeButton("Закончить") { _, _ ->
                    saveAndShowResult()
                }
                .show()
        } else {
            // ===== НЕПРАВИЛЬНЫЙ ОТВЕТ =====
            AlertDialog.Builder(this)
                .setTitle("Неправильно!")
                .setMessage("Правильно: ${currentSequence.joinToString("  ")}\n\n" +
                        "Ваш ответ: ${userNumbers.joinToString("  ")}")
                .setPositiveButton("Узнать результат") { _, _ ->
                    saveAndShowResult()
                }
                .show()
        }
    }

    /**
     * Сохраняет результат и показывает его.
     */
    private fun saveAndShowResult() {
        // Сохраняем результат текущей игры
        saveLastScore(currentMaxScore)

        // Если побит рекорд - сохраняем новый рекорд
        if (currentMaxScore > allTimeBest) {
            saveBestScore(currentMaxScore)
        }

        showResult()
    }

    /**
     * Показывает финальный результат с интерпретацией.
     */
    private fun showResult() {
        val isNewRecord = currentMaxScore > 0 &&
                currentMaxScore == allTimeBest &&
                currentMaxScore > 0

        val recordMessage = if (isNewRecord) {
            "\n\nНОВЫЙ РЕКОРД!"
        } else {
            "\n\nРекорд за всё время: $allTimeBest"
        }

        val interpretation = when {
            currentMaxScore >= 7 -> "Отлично! Ваша память в отличной форме!"
            currentMaxScore >= 5 -> "Хороший результат! Регулярные тренировки помогут улучшить его."
            currentMaxScore >= 3 -> "Неплохо! Тренируйтесь чаще."
            else -> "Начните тренировки сегодня! Это поможет улучшить память."
        }

        AlertDialog.Builder(this)
            .setTitle("Результат теста")
            .setMessage("Ваш результат: $currentMaxScore/10\n\n$interpretation$recordMessage")
            .setPositiveButton("Отлично!") { _, _ -> }
            .show()
    }

    // ==================== СТАТИСТИКА ====================

    /**
     * Показывает статистику и советы.
     */
    private fun showStatistics() {
        // Перезагружаем данные из SharedPreferences
        loadAllData()

        AlertDialog.Builder(this)
            .setTitle("Статистика")
            .setMessage("Рекорд за всё время: $allTimeBest\n\n" +
                    "Результат последней игры: $currentMaxScore\n\n" +
                    "Советы для улучшения памяти:\n" +
                    "• Тренируйтесь ежедневно\n" +
                    "• Высыпайтесь (7-8 часов)\n" +
                    "• Пейте достаточно воды\n" +
                    "• Учите стихи или языки")
            .setPositiveButton("Спасибо") { _, _ -> }
            .show()
    }
}