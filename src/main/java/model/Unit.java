package model;

public class Unit {
    public final int id; // card id
    public int hp;
    public final int owner; // 0 or 1
    public int row; // 0..7 global
    public int col; // 0..1

    public Unit(int id, int hp, int owner, int row, int col) {
        this.id = id; this.hp = hp; this.owner = owner; this.row = row; this.col = col;
    }
}
