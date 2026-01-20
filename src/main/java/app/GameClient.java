package app;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.*;
import javafx.scene.canvas.*;
import javafx.scene.control.*;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.stage.Stage;
import javafx.util.Duration;
import net.NetClient;
import net.Protocol;
import model.Card;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

public class GameClient extends Application {
    private Stage primaryStage;
    private Scene menuScene, gameScene, endScene;
    private Label opponentReadyLabel;
    private Label opponentSelectionLabel;
    private Button readyButton;
    private boolean isReady = false;
    private NetClient net = new NetClient();
    private int playerIndex = -1;
    private Canvas gameCanvas;
    private AtomicInteger elixir0 = new AtomicInteger(5), elixir1 = new AtomicInteger(5);
    private Label elixirLabel;
    private final List<UnitState> units = new ArrayList<>();
    private int towerDamage0 = 0;
    private int towerDamage1 = 0;
    private Label gameTimeLabel;
    private Label cycleTimeLabel;
    private Timeline gameTimer;
    private Timeline cycleTimer;
    private double cycleTimeRemaining = 3.0;

    private int prevTowerDamage0 = 0;
    private int prevTowerDamage1 = 0;
    private TextArea debugArea;
    private Integer selectedCardIndex = null;
    private final Button[] cardButtons = new Button[4];
    private final Card[] allCards = Card.defaultCards();
    private final List<ToggleButton> menuToggles = new ArrayList<>();
    private List<Integer> selectedCardIds = new ArrayList<>(); // Храним выбранные карты

    private static class UnitState {
        int owner;
        int cardId;
        int row;
        int col;
        int hp;
        UnitState(int owner, int cardId, int row, int col, int hp) {
            this.owner = owner; this.cardId = cardId; this.row = row; this.col = col; this.hp = hp;
        }
        @Override public String toString() {
            return owner + "," + cardId + "," + row + "," + col + "," + hp;
        }
    }

    @Override
    public void start(Stage stage) {
        primaryStage = stage;
        initMenu();
        initGame();
        initEnd();
        primaryStage.setTitle("ClashRoyaleLite - Client");
        primaryStage.setScene(menuScene);
        primaryStage.show();
        String ip = server.ConfigLoader.getServerIp();
        connectToServer(ip, 23456);
    }

    private void connectToServer(String host, int port) {
        new Thread(() -> {
            try {
                net.connect(host, port, this::onServerMessage);
            } catch (Exception e) {
                e.printStackTrace();
                Platform.runLater(() -> showAlert("Network", "Failed to connect to server: " + e.getMessage()));
            }
        }).start();
    }

    private void onServerMessage(String raw) {
        System.out.println("[CLIENT] recv: " + raw);
        var p = Protocol.parse(raw);
        if (p == null) return;
        switch (p.type) {
            case "ASSIGN":
                try {
                    playerIndex = Integer.parseInt(p.payload);
                    System.out.println("Assigned playerIndex = " + playerIndex);
                    resetGameState();
                } catch (Exception ignored) {}
                break;
            case "READY":
                try {
                    int who = Integer.parseInt(p.payload);
                    if (who != playerIndex) {
                        Platform.runLater(() -> opponentReadyLabel.setText("Противник готов"));
                    } else {
                        System.out.println("Server confirmed our READY");
                    }
                } catch (Exception ignored) {}
                break;
            case "NOTREADY":
                try {
                    int who = Integer.parseInt(p.payload);
                    if (who != playerIndex) {
                        Platform.runLater(() -> opponentReadyLabel.setText("Противник не готов"));
                    } else {
                        System.out.println("Server confirmed our NOTREADY");
                    }
                } catch (Exception ignored) {}
                break;
            case "SELECT":
                try {
                    String[] parts = p.payload.split(":", 2);
                    int from = Integer.parseInt(parts[0]);
                    String cards = parts.length > 1 ? parts[1] : "";
                    if (from != playerIndex) {
                        Platform.runLater(() -> opponentSelectionLabel.setText("Выбор противника: " + cards));
                    } else {
                        System.out.println("Our selection acknowledged by server: " + cards);
                        updateCardButtons(cards);
                    }
                } catch (Exception ignored) {}
                break;
            case "START_GAME":
                Platform.runLater(() -> {
                    resetGameState();
                    if (selectedCardIds.size() == 4 && playerIndex != -1) {
                        String payload = String.join(",", selectedCardIds.stream().map(Object::toString).toArray(String[]::new));
                        net.send(Protocol.make("SELECT", payload));
                        System.out.println("Auto-sent SELECT on START_GAME: " + payload);
                    }
                    startTimers(); // Запускаем таймеры при старте игры
                    primaryStage.setScene(gameScene);
                });
                break;
            case "DEPLOY":
                handleDeployMessage(p.payload);
                break;
            case "UPDATE":
                parseUpdatePayload(p.payload);
                Platform.runLater(this::resetCycleTimer); // Сбрасываем таймер цикла при каждом обновлении
                break;
            case "END":
                String score = p.payload;
                Platform.runLater(() -> {
                    stopTimers(); // Останавливаем таймеры при завершении игры
                    showEnd(score);
                });
                break;
            case "INFO":
                System.out.println("INFO from server: " + p.payload);
                break;
        }
    }

    private void resetGameState() {
        units.clear();
        selectedCardIndex = null;
        towerDamage0 = 0;
        towerDamage1 = 0;
        prevTowerDamage0 = 0;
        prevTowerDamage1 = 0;

        Platform.runLater(() -> {
            for (ToggleButton tb : menuToggles) {
                tb.setSelected(false);
            }
            if (readyButton != null) {
                readyButton.setDisable(true);
                readyButton.setText("Готов!");
                isReady = false;
            }
            if (opponentReadyLabel != null) {
                opponentReadyLabel.setText("Противник не готов");
            }
            if (opponentSelectionLabel != null) {
                opponentSelectionLabel.setText("Выбор противника: -");
            }
        });
    }

    // Внутри класса GameClient
    private int getVisualRow(int globalRow, int playerIndex) {
        // Для игрока 0: отображение без изменений
        // Для игрока 1: зеркальное отображение относительно центра поля
        return (playerIndex == 0) ? globalRow : 9 - globalRow;
    }

    private void handleDeployMessage(String payload) {
        try {
            String[] parts = payload.split(":", 2);
            if (parts.length < 2) return;
            int deployerIndex = Integer.parseInt(parts[0]);
            String[] deployData = parts[1].split(",");
            if (deployData.length < 3) return;
            int cardId = Integer.parseInt(deployData[0]);
            int localRow = Integer.parseInt(deployData[1]);
            int col = Integer.parseInt(deployData[2]);

            int globalRow;
            if (deployerIndex == 0) {
                // Игрок 0: его локальные строки 0-4 → глобальные 5-9 (нижняя половина поля)
                globalRow = 5 + localRow;
            } else {
                // Игрок 1: его локальные строки 0-4 → глобальные 4-0 (верхняя половина поля)
                globalRow = 4 - localRow;
            }

            Card card = null;
            for (Card c : allCards) {
                if (c.id == cardId) {
                    card = c;
                    break;
                }
            }

            if (card == null) return;
            UnitState newUnit = new UnitState(deployerIndex, cardId, globalRow, col, card.hp);

            Platform.runLater(() -> {
                boolean exists = false;
                for (UnitState u : units) {
                    if (u.owner == deployerIndex && u.row == globalRow && u.col == col) {
                        exists = true;
                        break;
                    }
                }
                if (!exists) {
                    units.add(newUnit);
                }
                redrawGame();
                appendDebug("Отображён юнит: " + newUnit);
            });
        } catch (Exception e) {
            System.err.println("Ошибка обработки DEPLOY: " + e.getMessage());
        }
    }

    private void updateCardButtons(String cardsPayload) {
        if (cardsPayload == null || cardsPayload.isEmpty()) return;
        String[] cardIds = cardsPayload.split(",");
        Platform.runLater(() -> {
            for (int i = 0; i < Math.min(4, cardIds.length); i++) {
                try {
                    int cardId = Integer.parseInt(cardIds[i]);
                    if (cardId >= 0 && cardId < allCards.length) {
                        Card card = allCards[cardId];
                        if (i < cardButtons.length && cardButtons[i] != null) {
                            // Устанавливаем текст и сохраняем объект карты в UserData
                            cardButtons[i].setText(card.name + " (" + card.cost + ")");
                            cardButtons[i].setUserData(card);
                        }
                    }
                } catch (NumberFormatException ignored) {}
            }
            updateCardButtonsUI(); // Обновляем интерфейс кнопок
        });
    }

    private void parseUpdatePayload(String pay) {
        try {
            String[] parts = pay.split("\\|", 5);
            int prev0 = towerDamage0;
            int prev1 = towerDamage1;

            if (parts.length >= 2) {
                int e0 = Integer.parseInt(parts[0]);
                int e1 = Integer.parseInt(parts[1]);
                elixir0.set(e0); elixir1.set(e1);
            }
            if (parts.length >= 4) {
                try { towerDamage0 = Integer.parseInt(parts[2]); } catch (Exception ignored) { towerDamage0 = 0; }
                try { towerDamage1 = Integer.parseInt(parts[3]); } catch (Exception ignored) { towerDamage1 = 0; }
            }
            units.clear();
            if (parts.length == 5) {
                String unitsPart = parts[4];
                if (unitsPart != null && !unitsPart.isBlank()) {
                    String[] unitEntries = unitsPart.split(";");
                    for (String u : unitEntries) {
                        if (u.isBlank()) continue;
                        String[] f = u.split(",");
                        if (f.length >= 5) {
                            try {
                                int owner = Integer.parseInt(f[0]);
                                int cardId = Integer.parseInt(f[1]);
                                int row = Integer.parseInt(f[2]);
                                int col = Integer.parseInt(f[3]);
                                int hp = Integer.parseInt(f[4]);
                                units.add(new UnitState(owner, cardId, row, col, hp));
                            } catch (NumberFormatException nfe) {
                                System.out.println("[CLIENT] bad unit entry: " + u);
                            }
                        }
                    }
                }
            }

            System.out.println("[CLIENT] parsed UPDATE: e0=" + elixir0 + " e1=" + elixir1
                    + " dmg0=" + towerDamage0 + " dmg1=" + towerDamage1 + " units=" + units.size());

            Platform.runLater(() -> {
                redrawGame();
                updateCardButtonsUI();
                if (towerDamage0 != prev0) {
                    String msg = "towerDamage0 changed: " + prev0 + " -> " + towerDamage0;
                    msg += " ; nearby units: " + describeNearbyUnitsForTower(0);
                    appendDebug(msg);
                }
                if (towerDamage1 != prev1) {
                    String msg = "towerDamage1 changed: " + prev1 + " -> " + towerDamage1;
                    msg += " ; nearby units: " + describeNearbyUnitsForTower(1);
                    appendDebug(msg);
                }
            });
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    private String describeNearbyUnitsForTower(int towerIndex) {
        if (gameCanvas == null) return "[no-canvas]";
        double w = gameCanvas.getWidth();
        double h = gameCanvas.getHeight();
        double minDim = Math.min(w, h);
        double cellSize = minDim * 0.07;
        double startX = w/2 - cellSize*1.5;
        double startY = h*0.1;
        double fieldHeight = cellSize * 10;

        // Башня вверху для обоих игроков (относительно их перспективы)
        double towerY = (towerIndex == 0) ? startY - cellSize*2 : startY + fieldHeight + cellSize;
        double towerX = startX + cellSize*1.5 - cellSize*1;
        double tx = towerX + cellSize*1;
        double ty = towerY + cellSize*1.5;
        List<String> near = new ArrayList<>();
        for (UnitState u : units) {
            double ux = startX + u.col*cellSize + cellSize*0.15 + (cellSize*0.7)/2;
            double uy = startY + u.row*cellSize + cellSize*0.15 + (cellSize*0.7)/2;
            double dist = Math.hypot(ux - tx, uy - ty);
            if (dist < Math.max(cellSize, cellSize) * 1.5) {
                near.add("[" + u.owner + ":" + u.cardId + " r" + u.row + "c" + u.col + " hp" + u.hp + "]");
            }
        }
        if (near.isEmpty()) return "none";
        return String.join(",", near);
    }

    private void appendDebug(String text) {
        String t = "[" + new Date() + "] " + text + "\n";
        System.out.println(t);
        if (debugArea != null) {
            debugArea.appendText(t);
            debugArea.setScrollTop(Double.MAX_VALUE);
        }
    }

    private void initMenu() {
        VBox root = new VBox(15);
        root.setAlignment(Pos.CENTER);
        root.setPadding(new Insets(20));
        opponentReadyLabel = new Label("Противник не готов");
        opponentReadyLabel.setFont(new Font(16));
        opponentSelectionLabel = new Label("Выбор противника: -");
        opponentSelectionLabel.setFont(new Font(14));

        readyButton = new Button("Готов!");
        readyButton.setDisable(true);
        readyButton.setPrefSize(200, 40);
        readyButton.setStyle("-fx-font-size: 16px;");
        readyButton.setOnAction(e -> toggleReady());

        FlowPane cards = new FlowPane();
        cards.setHgap(20);
        cards.setVgap(20);
        cards.setAlignment(Pos.CENTER);
        menuToggles.clear();
        // Создаем кнопки с детальной информацией о картах
        for (int i = 0; i < allCards.length; i++) {
            Card card = allCards[i];
            ToggleButton tb = new ToggleButton();
            tb.setPrefSize(220, 110);
            tb.setStyle("-fx-font-size: 14px; -fx-alignment: center;");

            // Форматируем текст кнопки с характеристиками карты
            String buttonText = String.format("%s\nHP: %d\nУрон: %d\nСтоимость: %d",
                    card.name, card.hp, card.atk, card.cost);
            tb.setText(buttonText);
            tb.setUserData(card.id); // Сохраняем ID карты

            tb.setOnAction(ev -> {
                long selected = menuToggles.stream().filter(ToggleButton::isSelected).count();
                // Обновляем список выбранных карт
                selectedCardIds.clear();
                for (ToggleButton toggle : menuToggles) {
                    if (toggle.isSelected()) {
                        selectedCardIds.add((Integer) toggle.getUserData());
                    }
                }
                readyButton.setDisable(selectedCardIds.size() < 4);
            });

            menuToggles.add(tb);
            cards.getChildren().add(tb);
        }

        Button showDeckButton = new Button("Показать колоду противнику");
        showDeckButton.setPrefSize(250, 40);
        showDeckButton.setStyle("-fx-font-size: 16px;");
        showDeckButton.setOnAction(e -> {
            if (selectedCardIds.size() < 4) {
                showAlert("Выбор карт", "Выберите ровно 4 карты для показа!");
                return;
            }

            // Отправляем SELECT для показа противнику
            String payload = String.join(",", selectedCardIds.stream().map(Object::toString).toArray(String[]::new));
            net.send(Protocol.make("SELECT", payload));
            System.out.println("Sent SELECT to show to opponent: " + payload);
            showAlert("Колода отправлена", "Ваша колода показана противнику");
        });

        root.getChildren().addAll(opponentReadyLabel, opponentSelectionLabel, new Label("Выберите 4 карты:"), cards, showDeckButton, readyButton);
        menuScene = new Scene(root, 950, 800); // Увеличиваем размер окна для размещения больших кнопок
    }

    private void toggleReady() {
        if (!isReady) {
            // Проверяем, выбраны ли 4 карты
            if (selectedCardIds.size() < 4) {
                showAlert("Выбор карт", "Выберите ровно 4 карты перед тем как нажать 'Готов!'");
                return;
            }

            // Отправляем READY без автоматической отправки SELECT
            net.send(Protocol.make("READY", ""));
            isReady = true;
            readyButton.setText("Не готов");
        } else {
            net.send(Protocol.make("NOTREADY", ""));
            isReady = false;
            readyButton.setText("Готов!");
        }
    }

    private void initGame() {
        BorderPane root = new BorderPane();
        gameCanvas = new Canvas(900, 750);
        elixirLabel = new Label("Эликсир: ?");
        elixirLabel.setFont(new Font(18));
        elixirLabel.setTextFill(Color.web("#FF1493"));

        debugArea = new TextArea();
        debugArea.setEditable(false);
        debugArea.setPrefRowCount(6);
        debugArea.setPrefColumnCount(25);
        debugArea.setFont(new Font(12));

        // Создаем крупные таймеры с контрастными цветами
        gameTimeLabel = new Label("Время: 2:00");
        gameTimeLabel.setFont(new Font(20));
        gameTimeLabel.setTextFill(Color.RED); // Красный цвет для основного времени
        gameTimeLabel.setStyle("-fx-font-weight: bold;");

        cycleTimeLabel = new Label("След. ход: 3.0");
        cycleTimeLabel.setFont(new Font(20));
        cycleTimeLabel.setTextFill(Color.BLUE); // Синий цвет как у первого игрока
        cycleTimeLabel.setStyle("-fx-font-weight: bold;");

        VBox right = new VBox(12);
        right.setAlignment(Pos.TOP_CENTER);
        right.setPadding(new Insets(10));
        right.getChildren().addAll(new Label("Ваши карты"));
        for (int i=0; i<4; i++) {
            Button b = new Button("Card " + i);
            b.setPrefSize(150, 50);
            b.setStyle("-fx-font-size: 14px;");
            cardButtons[i] = b;
            int idx = i;
            b.setOnAction(e -> selectCard(idx));
            right.getChildren().add(b);
        }

        VBox left = new VBox(15);
        left.setAlignment(Pos.TOP_LEFT);
        left.setPadding(new Insets(10));
        // Добавляем таймеры над debug log
        left.getChildren().addAll(elixirLabel, gameTimeLabel, cycleTimeLabel, new Label("Debug log:"), debugArea);

        root.setCenter(gameCanvas);
        root.setRight(right);
        root.setLeft(left);
        gameScene = new Scene(root, 1400, 800);
        gameCanvas.addEventHandler(MouseEvent.MOUSE_CLICKED, ev -> handleCanvasClick(ev));
        redrawGame();
    }

    private String formatTime(int seconds) {
        int minutes = seconds / 60;
        seconds = seconds % 60;
        return String.format("%d:%02d", minutes, seconds);
    }

    private void startTimers() {
        stopTimers(); // Останавливаем старые таймеры если есть

        // Общий таймер на 2 минуты
        AtomicInteger remaining = new AtomicInteger(120); // 120 секунд = 2 минуты
        gameTimeLabel.setText("Время: " + formatTime(remaining.get()));

        gameTimer = new Timeline(new KeyFrame(Duration.seconds(1), e -> {
            int sec = remaining.decrementAndGet();
            gameTimeLabel.setText("Время: " + formatTime(sec));
            if (sec <= 0) {
                gameTimer.stop();
            }
        }));
        gameTimer.setCycleCount(121); // 120 секунд + 1 для последнего кадра
        gameTimer.play();

        // Таймер игрового цикла (3 секунды с десятыми)
        cycleTimeRemaining = 3.0;
        cycleTimeLabel.setText("След. ход: " + String.format("%.1f", cycleTimeRemaining));

        cycleTimer = new Timeline(new KeyFrame(Duration.millis(100), e -> {
            cycleTimeRemaining = Math.max(0, cycleTimeRemaining - 0.1);
            cycleTimeLabel.setText("След. ход: " + String.format("%.1f", cycleTimeRemaining));
        }));
        cycleTimer.setCycleCount(Timeline.INDEFINITE);
        cycleTimer.play();
    }

    private void resetCycleTimer() {
        if (cycleTimer != null) {
            cycleTimer.stop();
        }
        cycleTimeRemaining = 3.0;
        cycleTimeLabel.setText("След. ход: " + String.format("%.1f", cycleTimeRemaining));

        cycleTimer = new Timeline(new KeyFrame(Duration.millis(100), e -> {
            cycleTimeRemaining = Math.max(0, cycleTimeRemaining - 0.1);
            cycleTimeLabel.setText("След. ход: " + String.format("%.1f", cycleTimeRemaining));
        }));
        cycleTimer.setCycleCount(Timeline.INDEFINITE);
        cycleTimer.play();
    }

    private void stopTimers() {
        if (gameTimer != null) {
            gameTimer.stop();
            gameTimer = null;
        }
        if (cycleTimer != null) {
            cycleTimer.stop();
            cycleTimer = null;
        }
    }

    private void selectCard(int cardIndex) {
        if (selectedCardIndex != null && selectedCardIndex == cardIndex) {
            selectedCardIndex = null;
            cardButtons[cardIndex].setStyle("");
            redrawGame(); // Обновляем отрисовку, чтобы убрать подсветку
            return;
        }
        if (selectedCardIndex != null) {
            cardButtons[selectedCardIndex].setStyle("");
        }
        selectedCardIndex = cardIndex;
        cardButtons[cardIndex].setStyle("-fx-border-color: yellow; -fx-border-width: 3px; -fx-border-radius: 5px;");
        redrawGame(); // Обновляем отрисовку для подсветки своей половины
    }

    private void updateCardButtonsUI() {
        int currentElixir = (playerIndex == 0) ? elixir0.get() : elixir1.get();
        Platform.runLater(() -> {
            for (int i = 0; i < 4; i++) {
                Button button = cardButtons[i];
                if (button == null) continue;

                Card card = (Card) button.getUserData();
                if (card == null) {
                    if (i < selectedCardIds.size()) {
                        int cardId = selectedCardIds.get(i);
                        for (Card c : allCards) {
                            if (c.id == cardId) {
                                button.setText(c.name + " (" + c.cost + ")");
                                button.setUserData(c);
                                card = c;
                                break;
                            }
                        }
                    }

                    if (card == null) {
                        button.setDisable(true);
                        continue;
                    }
                }

                boolean hasEnoughElixir = currentElixir >= card.cost;
                button.setDisable(!hasEnoughElixir);

                // Снимаем выбор если карта стала недоступна
                if (!hasEnoughElixir && selectedCardIndex != null && selectedCardIndex == i) {
                    selectedCardIndex = null;
                    button.setStyle("");
                }

                // Обновляем стиль только для доступных карт
                if (hasEnoughElixir && selectedCardIndex != null && selectedCardIndex == i) {
                    button.setStyle("-fx-border-color: yellow; -fx-border-width: 3px; -fx-border-radius: 5px;");
                } else {
                    button.setStyle("");
                }
            }
        });
    }

    private void handleCanvasClick(MouseEvent ev) {
        if (selectedCardIndex == null) {
            appendDebug("Сначала выберите карту");
            return;
        }

        Button selectedButton = cardButtons[selectedCardIndex];
        Card selectedCard = (Card) selectedButton.getUserData();

        if (selectedCard == null) {
            appendDebug("Ошибка данных о карте!");
            selectedCardIndex = null;
            selectedButton.setStyle("");
            redrawGame();
            return;
        }

        int cardCost = selectedCard.cost;
        int currentElixir = (playerIndex == 0) ? elixir0.get() : elixir1.get();

        if (currentElixir < cardCost) {
            appendDebug("Недостаточно эликсира! Нужно: " + cardCost + ", есть: " + currentElixir);
            selectedCardIndex = null;
            selectedButton.setStyle("");
            redrawGame();
            return;
        }

        double w = gameCanvas.getWidth();
        double h = gameCanvas.getHeight();
        double minDim = Math.min(w, h);
        double cellSize = minDim * 0.075; // ТОТ ЖЕ САМЫЙ РАЗМЕР КЛЕТКИ
        double startX = w/2 - cellSize*1.5;
        double startY = h*0.12; // ТОТ ЖЕ САМЫЙ СДВИГ

        double relX = ev.getX() - startX;
        double relY = ev.getY() - startY;

        if (relX < 0 || relX > cellSize*3 || relY < 0 || relY > cellSize*10) {
            appendDebug("Клик вне игрового поля");
            return;
        }

        int col = (int)(relX / cellSize);
        int visualRow = (int)(relY / cellSize);

        int globalRow;
        if (playerIndex == 0) {
            globalRow = visualRow;
        } else {
            globalRow = 9 - visualRow;
        }

        boolean isPlayerHalf;
        if (playerIndex == 0) {
            isPlayerHalf = globalRow >= 5 && globalRow <= 9;
        } else {
            isPlayerHalf = globalRow >= 0 && globalRow <= 4;
        }

        if (!isPlayerHalf) {
            appendDebug("Нельзя размещать юнита на половине противника");
            return;
        }

        boolean cellOccupied = false;
        for (UnitState u : units) {
            if (u.row == globalRow && u.col == col) {
                cellOccupied = true;
                break;
            }
        }

        if (cellOccupied) {
            appendDebug("Клетка уже занята другим юнитом");
            return;
        }

        int localRow;
        if (playerIndex == 0) {
            localRow = globalRow - 5;
        } else {
            localRow = 4 - globalRow;
        }

        int cardId = selectedCard.id;
        String payload = cardId + "," + localRow + "," + col;
        System.out.println("[CLIENT] sending DEPLOY (playerIndex=" + playerIndex + "): " + payload);
        appendDebug("Размещение карты: " + payload + " (стоимость: " + cardCost + ", остаток эликсира: " + (currentElixir - cardCost) + ")");
        net.send(Protocol.make("DEPLOY", payload));

        selectedCardIndex = null;
        if (selectedButton != null) {
            selectedButton.setStyle("");
        }
        redrawGame();
    }

    private void redrawGame() {
        GraphicsContext g = gameCanvas.getGraphicsContext2D();
        g.setFill(Color.LIGHTGRAY);
        g.fillRect(0,0,gameCanvas.getWidth(),gameCanvas.getHeight());

        double w = gameCanvas.getWidth();
        double h = gameCanvas.getHeight();
        double minDim = Math.min(w, h);
        double cellSize = minDim * 0.075; // Немного увеличиваем размер клетки
        double startX = w/2 - cellSize*1.5;
        double startY = h*0.12; // Немного сдвигаем поле вниз
        double fieldHeight = cellSize * 10;

        // Фон поля
        g.setFill(Color.DARKOLIVEGREEN);
        g.fillRect(startX - 10, startY - 10, cellSize*3 + 20, fieldHeight + 20);

        // Рисуем сетку (квадратные клетки)
        g.setStroke(Color.BLACK);
        g.setLineWidth(2);

        // Горизонтальные линии
        for (int r = 0; r <= 10; r++) {
            double y = startY + r * cellSize;
            g.strokeLine(startX, y, startX + cellSize*3, y);
        }

        // Вертикальные линии
        for (int c = 0; c <= 3; c++) {
            double x = startX + c * cellSize;
            g.strokeLine(x, startY, x, startY + fieldHeight);
        }

        // Разделительная линия между половинами
        g.setStroke(Color.GOLD);
        g.setLineWidth(3);
        double midY = startY + cellSize*5;
        g.strokeLine(startX, midY, startX + cellSize*3, midY);

        // Рисуем башни
        double towerW = cellSize*2, towerH = cellSize*2.5;

        // ВАЖНО: Для обоих игроков своя башня должна быть внизу, вражеская - вверху
        int yourDamage = (playerIndex == 0) ? towerDamage0 : towerDamage1;
        int enemyDamage = (playerIndex == 0) ? towerDamage1 : towerDamage0;

        // Ваша башня (всегда внизу визуально)
        double yourTowerY = startY + fieldHeight + 10;
        double yourTowerX = startX + cellSize*1.5 - towerW/2;
        g.setFill(Color.DARKSEAGREEN);
        g.fillRect(yourTowerX, yourTowerY, towerW, towerH);
        g.setFill(Color.WHITE);
        g.setFont(new Font(20));
        // Компактное расположение текста
        g.fillText("ТЫ", yourTowerX + 5, yourTowerY + 25);
        g.fillText("Урон: " + yourDamage, yourTowerX + 5, yourTowerY + 50);

        // Башня противника (всегда вверху визуально)
        double enemyTowerY = startY - towerH - 10;
        double enemyTowerX = startX + cellSize*1.5 - towerW/2;
        g.setFill(Color.DARKSALMON);
        g.fillRect(enemyTowerX, enemyTowerY, towerW, towerH);
        g.setFill(Color.WHITE);
        g.setFont(new Font(20));
        // Компактное расположение текста
        g.fillText("ВРАГ", enemyTowerX + 5, enemyTowerY + 90);
        g.fillText("Урон: " + enemyDamage, enemyTowerX + 5, enemyTowerY + 115);

        // Рисуем юнитов
        for (UnitState u : units) {
            drawUnit(g, u, cellSize, startX, startY, false);
        }

        // Информация об эликсире - убираем "(вы)"
        String eText;
        if (playerIndex == 0) eText = "Эликсир: " + elixir0.get() + "/10";
        else if (playerIndex == 1) eText = "Эликсир: " + elixir1.get() + "/10";
        else eText = "Эликсир: " + elixir0.get() + "|" + elixir1.get();
        elixirLabel.setText(eText);
        elixirLabel.setTextFill(Color.web("#FF1493")); // Яркий неоновый розовый

        // Подсветка выбранной карты - ТОЛЬКО СВОЯ ПОЛОВИНА
        if (selectedCardIndex != null) {
            g.setStroke(Color.YELLOW);
            g.setLineWidth(3.0);

            if (playerIndex == 0) {
                double startYPlayer = startY + cellSize * 5;
                g.strokeRect(startX, startYPlayer, cellSize * 3, cellSize * 5);
            } else {
                double startYPlayer = startY + cellSize * 5; // Нижняя половина визуально
                g.strokeRect(startX, startYPlayer, cellSize * 3, cellSize * 5);
            }
        }
    }

    private void drawUnit(GraphicsContext g, UnitState u, double cellSize, double startX, double startY, boolean isLocal) {
        // Вычисляем визуальную строку на основе глобальной и индекса игрока
        int visualRow = getVisualRow(u.row, playerIndex);

        double ux = startX + u.col*cellSize + cellSize*0.15;
        double uy = startY + visualRow*cellSize + cellSize*0.15;
        double uw = cellSize*0.7;
        double uh = cellSize*0.7;

        // Цвет зависит от того, чей это юнит (относительно текущего игрока)
        boolean isOwnUnit = (u.owner == playerIndex);
        if (isOwnUnit) {
            g.setFill(Color.BLUE); // Синий для своих юнитов
        } else {
            g.setFill(Color.RED); // Красный для чужих юнитов
        }
        g.fillOval(ux, uy, Math.max(12, uw), Math.max(12, uh));

        g.setFill(Color.WHITE);
        g.setFont(new Font(12));
        String lab = allCards[u.cardId].name.substring(0, 1) + " " + u.hp;
        g.fillText(lab, ux+5, uy+uh/2);
    }

    private void initEnd() {
        VBox root = new VBox(15);
        root.setAlignment(Pos.CENTER);
        root.setPadding(new Insets(30));
        Label result = new Label("Игра окончена");
        result.setFont(new Font(20));
        Button toMenu = new Button("В меню");
        toMenu.setPrefSize(150, 40);
        toMenu.setStyle("-fx-font-size: 16px;");
        toMenu.setOnAction(e -> {
            selectedCardIndex = null;
            resetGameState();
            stopTimers(); // Останавливаем таймеры
            primaryStage.setScene(menuScene);
        });
        root.getChildren().addAll(result, toMenu);
        endScene = new Scene(root, 700, 500);
    }

    private void showEnd(String score) {
        resetGameState();

        VBox root = new VBox(20);
        root.setAlignment(Pos.CENTER);
        root.setPadding(new Insets(30));
        String[] parts = score.split("/");
        int d0 = Integer.parseInt(parts[0]);
        int d1 = Integer.parseInt(parts[1]);

        Label scoreLabel = new Label("Урон башен: " + d0 + " / " + d1);
        scoreLabel.setFont(new Font(20));

        String outcome;
        if ((playerIndex == 0 && d0 > d1) || (playerIndex == 1 && d1 > d0)) {
            outcome = "ПОБЕДА!";
            root.setStyle("-fx-background-color: #90EE90;");
        } else if ((playerIndex == 0 && d0 < d1) || (playerIndex == 1 && d1 < d0)) {
            outcome = "ПОРАЖЕНИЕ...";
            root.setStyle("-fx-background-color: #FFB6C1;");
        } else {
            outcome = "НИЧЬЯ";
            root.setStyle("-fx-background-color: #E6E6FA;");
        }

        Label top = new Label(outcome);
        top.setFont(new Font(32));
        top.setStyle("-fx-font-weight: bold;");

        Button toMenu = new Button("В меню");
        toMenu.setPrefSize(180, 50);
        toMenu.setStyle("-fx-font-size: 18px;");
        toMenu.setOnAction(e -> {
            resetGameState();
            primaryStage.setScene(menuScene);
        });

        root.getChildren().addAll(top, scoreLabel, toMenu);
        endScene = new Scene(root, 700, 500);
        primaryStage.setScene(endScene);
    }

    private void showAlert(String t, String m) {
        Alert a = new Alert(Alert.AlertType.INFORMATION, m, ButtonType.OK);
        a.setTitle(t);
        a.setHeaderText(t);
        a.showAndWait();
    }

    public static void main(String[] args) { launch(args); }
}