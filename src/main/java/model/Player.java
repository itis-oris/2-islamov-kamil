package model;

import java.util.*;

public class Player {
    public final int index;
    public boolean ready = false;
    public List<Integer> selected = new ArrayList<>();
    public int elixir = 5;
    public int towerDamage = 0;

    public Player(int index) { this.index = index; }
}
