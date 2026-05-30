package com.example.mindtracker

// ==================== ИМПОРТЫ ====================
import android.app.NotificationChannel      // Для создания канала уведомлений (Android 8+)
import android.app.NotificationManager     // Для управления уведомлениями
import android.content.Context             // Для доступа к SharedPreferences
import android.os.Build                    // Для проверки версии Android
import android.os.Bundle                   // Для сохранения состояния Activity
import android.os.Handler                  // Для задержки выполнения кода
import android.os.Looper                   // Для работы Handler в главном потоке
import android.text.InputType              // Для типа клавиатуры (цифровая)
import android.widget.Button               // Кнопка
import android.widget.EditText             // Поле ввода текста
import androidx.appcompat.app.AlertDialog  // Всплывающее диалоговое окно
import androidx.appcompat.app.AppCompatActivity  // Базовая Activity с поддержкой старых версий
import androidx.core.app.NotificationCompat      // Для создания уведомлений
import androidx.work.PeriodicWorkRequestBuilder  // Для периодических фоновых задач
import androidx.work.WorkManager                 // Менеджер фоновых задач
import androidx.work.Worker                      // Базовый класс для фоновой работы
import androidx.work.WorkerParameters            // Параметры фоновой работы
import java.util.concurrent.TimeUnit             // Для указания времени (дни, часы, минуты)

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
 * @version 1.0
 */
class MainActivity : AppCompatActivity() {

    // ==================== ПЕРЕМЕННЫЕ СОСТОЯНИЯ ====================

    /** Текущий уровень сложности (1, 2, 3...). На уровне 1 показывается 3 цифры */
    private var currentLevel = 1

    /** Список цифр, которые нужно запомнить. Пример: [5, 2, 8] */
    private var currentSequence = mutableListOf<Int>()

    /**
     * Результат последней игры - максимальный достигнутый уровень.
     * Пример: если прошли уровни 1,2,3 и ошиблись на 4-м, то результат = 3
     */
    private var currentMaxScore = 0

    /** Рекорд за всё время использования приложения. Сохраняется навсегда */
    private var allTimeBest = 0

    // ==================== ЖИЗНЕННЫЙ ЦИКЛ ACTIVITY ====================

    /**
     * Вызывается при создании Activity.
     * Здесь происходит вся начальная настройка приложения.
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)  // Загружаем интерфейс из XML-файла

        // ===== ЗАПРОС РАЗРЕШЕНИЯ НА УВЕДОМЛЕНИЯ (Android 13+) =====
        // Начиная с Android 13 (API 33) нужно явно запрашивать разрешение на показ уведомлений
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissions(
                arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
                1001  // Код запроса (может быть любым)
            )
        }

        // ===== ЗАГРУЗКА СОХРАНЁННЫХ ДАННЫХ =====
        loadAllData()  // Загружаем рекорд и результат последней игры

        // ===== ЗАПУСК ЕЖЕДНЕВНЫХ НАПОМИНАНИЙ =====
        setupReminders()

        // ===== НАСТРОЙКА КНОПОК =====

        // Кнопка "Начать тренировку" - запускает тест памяти
        findViewById<Button>(R.id.btnMemoryTest).setOnClickListener {
            startMemoryTest()
        }

        // Кнопка "Статистика" - показывает рекорды и советы
        findViewById<Button>(R.id.btnStatistics).setOnClickListener {
            showStatistics()
        }
    }

    // ==================== РАБОТА С ДАННЫМИ (SharedPreferences) ====================

    /**
     * Сохраняет рекорд за всё время.
     * @param score Новый рекорд
     */
    private fun saveBestScore(score: Int) {
        // Получаем доступ к файлу настроек "mindtracker_prefs"
        val prefs = getSharedPreferences("mindtracker_prefs", Context.MODE_PRIVATE)
        // Сохраняем целое число с ключом "best_score"
        prefs.edit().putInt("best_score", score).apply()  // apply() - асинхронная запись
        allTimeBest = score
        println("Сохранён рекорд: $score")  // Лог для отладки
    }

    /**
     * Сохраняет результат последней игры.
     * @param score Результат последней игры
     */
    private fun saveLastScore(score: Int) {
        val prefs = getSharedPreferences("mindtracker_prefs", Context.MODE_PRIVATE)
        prefs.edit().putInt("last_score", score).apply()
        println("Сохранён последний результат: $score")
    }

    /**
     * Загружает все сохранённые данные при запуске приложения.
     * Если данных нет (первый запуск), возвращается 0.
     */
    private fun loadAllData() {
        val prefs = getSharedPreferences("mindtracker_prefs", Context.MODE_PRIVATE)
        allTimeBest = prefs.getInt("best_score", 0)      // Рекорд (по умолчанию 0)
        currentMaxScore = prefs.getInt("last_score", 0)  // Последний результат (по умолчанию 0)
        println("Загружено - Рекорд: $allTimeBest, Последний: $currentMaxScore")
    }

    // ==================== УВЕДОМЛЕНИЯ (WorkManager) ====================

    /**
     * Настраивает ежедневные напоминания через WorkManager.
     * Уведомления будут приходить каждый день в 10:00 утра.
     */
    private fun setupReminders() {
        // Создаём периодическую задачу: повторять 1 раз в день
        val workRequest = PeriodicWorkRequestBuilder<ReminderWorker>(
            1, TimeUnit.DAYS  // Интервал: 1 день
        ).setInitialDelay(10, TimeUnit.HOURS)  // Первое уведомление через 10 часов (в 10 утра)
            .build()

        // Запускаем задачу
        WorkManager.getInstance(this).enqueue(workRequest)
    }

    /**
     * Внутренний класс для выполнения фоновой работы (отправка уведомления).
     * WorkManager вызывает doWork() в фоновом потоке.
     */
    class ReminderWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

        /**
         * Главный метод, выполняемый в фоне.
         * @return Result.success() - задача выполнена успешно
         */
        override fun doWork(): Result {
            createNotificationChannel()  // Создаём канал (нужно для Android 8+)
            sendNotification()           // Отправляем уведомление
            return Result.success()
        }

        /**
         * Создаёт канал уведомлений (требуется для Android 8+).
         * Канал определяет важность и поведение уведомления.
         */
        private fun createNotificationChannel() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    "memory_channel",           // ID канала
                    "Тренировка памяти",        // Имя канала (видно пользователю)
                    NotificationManager.IMPORTANCE_HIGH  // Высокая важность (звук + всплывание)
                ).apply {
                    description = "Напоминания о тренировке памяти"  // Описание канала
                }
                val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE)
                        as NotificationManager
                manager.createNotificationChannel(channel)
            }
        }

        /**
         * Формирует и отправляет уведомление.
         */
        private fun sendNotification() {
            val notification = NotificationCompat.Builder(applicationContext, "memory_channel")
                .setSmallIcon(android.R.drawable.ic_menu_edit)  // Иконка уведомления
                .setContentTitle("Тренируйте память!")       // Заголовок
                .setContentText("Пройдите тест за 2 минуты, чтобы улучшить когнитивные функции")
                .setPriority(NotificationCompat.PRIORITY_HIGH)  // Высокий приоритет
                .setAutoCancel(true)  // Уведомление исчезает после нажатия
                .build()

            val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE)
                    as NotificationManager
            manager.notify(System.currentTimeMillis().toInt(), notification)
        }
    }

    // ==================== ЛОГИКА ТЕСТА ПАМЯТИ ====================

    /**
     * Запускает новый тест памяти.
     * Сбрасывает уровень и генерирует первую последовательность цифр.
     */
    private fun startMemoryTest() {
        currentLevel = 1                    // Начинаем с первого уровня
        // Не обнуляем currentMaxScore! Он хранит результат прошлой игры для статистики
        generateNewSequence()               // Создаём последовательность цифр
        showMemoryDialog()                  // Показываем диалог с цифрами для запоминания
    }

    /**
     * Генерирует последовательность случайных цифр.
     * Длина последовательности = текущий уровень + 2 (уровень 1 → 3 цифры, уровень 2 → 4 цифры и т.д.)
     */
    private fun generateNewSequence() {
        val length = currentLevel + 2               // Вычисляем длину
        currentSequence.clear()                      // Очищаем старую последовательность
        repeat(length) {
            currentSequence.add((1..9).random())    // Добавляем случайную цифру от 1 до 9
        }
    }

    /**
     * Показывает диалог с последовательностью цифр.
     * Через 3 секунды диалог автоматически переключится на поле ввода.
     */
    private fun showMemoryDialog() {
        val numbersText = currentSequence.joinToString("  ")  // [5,2,8] → "5  2  8"

        AlertDialog.Builder(this)
            .setTitle("Тест памяти - Уровень $currentLevel")
            .setMessage("Запомните последовательность:\n\n$numbersText\n\nЧерез 3 секунды нужно будет ввести цифры")
            .setPositiveButton("Готово") { _, _ ->
                // Задержка 3 секунды перед показом поля ввода
                Handler(Looper.getMainLooper()).postDelayed({
                    showInputDialog()
                }, 3000)  // 3000 миллисекунд = 3 секунды
            }
            .setNegativeButton("Отмена") { _, _ -> }  // Отмена - ничего не делаем
            .setCancelable(false)  // Запрещаем закрыть диалог нажатием вне его
            .show()
    }

    /**
     * Показывает диалог с полем для ввода ответа.
     * Пользователь вводит цифры подряд (например: 528)
     */
    private fun showInputDialog() {
        val input = EditText(this).apply {
            hint = "Введите цифры подряд (например: 528)"      // Подсказка в поле
            inputType = InputType.TYPE_CLASS_NUMBER            // Только цифровая клавиатура
        }

        AlertDialog.Builder(this)
            .setTitle("Введите последовательность")
            .setMessage("Введите все цифры подряд без пробелов:")
            .setView(input)  // Добавляем поле ввода в диалог
            .setPositiveButton("Проверить") { _, _ ->
                val userInput = input.text.toString().trim()
                checkAnswer(userInput)
            }
            .setNegativeButton("Сдаюсь") { _, _ ->
                saveAndShowResult()  // Досрочное завершение теста
            }
            .setCancelable(false)
            .show()
    }

    /**
     * Проверяет ответ пользователя.
     * @param userInput Строка, введённая пользователем (например: "528")
     */
    private fun checkAnswer(userInput: String) {
        // Преобразуем "528" в список [5, 2, 8]
        // map - каждый символ превращаем в число, filterNotNull - убираем не-цифры
        val userNumbers = userInput.map { it.toString().toIntOrNull() }
            .filterNotNull()

        // Сравниваем введённые цифры с правильной последовательностью
        if (userNumbers == currentSequence) {
            // ===== ПРАВИЛЬНЫЙ ОТВЕТ =====
            currentLevel++  // Повышаем уровень

            val newScore = currentLevel - 1  // Текущий результат (максимальный пройденный уровень)
            if (newScore > currentMaxScore) {
                currentMaxScore = newScore   // Обновляем лучший результат сессии
            }

            // Диалог успеха - предлагаем продолжить или закончить
            AlertDialog.Builder(this)
                .setTitle("Правильно!")
                .setMessage("Уровень ${currentLevel - 1} пройден!\n\nТекущий результат: $currentMaxScore")
                .setPositiveButton("Далее") { _, _ ->
                    generateNewSequence()  // Генерируем новую, более длинную последовательность
                    showMemoryDialog()     // Показываем следующий уровень
                }
                .setNegativeButton("Закончить") { _, _ ->
                    saveAndShowResult()    // Сохраняем результат и завершаем
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
     * Сохраняет результат последней игры и показывает его.
     * Обновляет рекорд, если текущий результат его побил.
     */
    private fun saveAndShowResult() {
        println("Сохраняем результат: $currentMaxScore")

        // Сохраняем результат последней игры
        saveLastScore(currentMaxScore)

        // Если побит рекорд - сохраняем новый рекорд
        if (currentMaxScore > allTimeBest) {
            saveBestScore(currentMaxScore)
        }

        // Показываем диалог с результатом
        showResult()
    }

    /**
     * Показывает финальный результат теста с медицинской интерпретацией.
     * Интерпретация основана на нормативах теста Digit Span:
     * - 7+ цифр: отличный результат
     * - 5-6: хороший
     * - 3-4: средний
     * - 0-2: требует тренировки
     */
    private fun showResult() {
        // Проверяем, установлен ли новый рекорд
        val isNewRecord = currentMaxScore > 0 &&
                currentMaxScore == allTimeBest &&
                currentMaxScore > 0

        val recordMessage = if (isNewRecord) {
            "\n\nНОВЫЙ РЕКОРД!"
        } else {
            "\n\nРекорд за всё время: $allTimeBest"
        }

        // Медицинская интерпретация результата (по шкале Digit Span)
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
     * Показывает статистику: рекорд за всё время, последний результат и советы.
     * Перед показом перезагружает данные из SharedPreferences.
     */
    private fun showStatistics() {
        // Перезагружаем данные (на случай, если они изменились в другой сессии)
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