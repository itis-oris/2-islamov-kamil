package model;

public class Card {
    public final int id;
    public final String name;
    public final int cost;
    public final int hp;
    public final int atk;

    public Card(int id, String name, int cost, int hp, int atk) {
        this.id = id;
        this.name = name;
        this.cost = cost;
        this.hp = hp;
        this.atk = atk;
    }

    public static Card[] defaultCards() {
        return new Card[] {
                new Card(0, "Assassin", 2, 100, 60),   // Быстрый убийца: побеждает Raider при атаке первым
                new Card(1, "Raider",   2, 225, 25),   // Живучий контроль: выживает против Assassin при выгодном ходе
                new Card(2, "Kamikaze", 3, 20, 300),   // Убивает Champion/Prince, но уязвим к дешёвым юнитам
                new Card(3, "Champion", 4, 350, 60),   // Надёжный танк средней игры
                new Card(4, "Prince",   5, 600, 85),   // Сильный средний юнит, проигрывает Titan
                new Card(5, "Destroyer",4, 180, 110),  // Гарантированно убивает Champion, слаб против танков
                new Card(6, "Guardian", 6, 700, 60),   // Танк с умеренным уроном, уступает Titan
                new Card(7, "Titan",    7, 750, 120)   // Финальный босс: сильный, но не непобедимый
        };
    }
}