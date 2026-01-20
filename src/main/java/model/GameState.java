package model;

import java.util.*;

public class GameState {
    public final Card[] cards = Card.defaultCards();
    public final List<Unit> units = new ArrayList<>();
    public final Player[] players = { new Player(0), new Player(1) };
    private int matchTimeSeconds = 120;
    private int elapsed = 0;
    private boolean inMatch = false;

    public void setPlayerSelection(int playerIndex, String payload) {
        Player p = players[playerIndex];
        p.selected.clear();
        if (payload == null || payload.isEmpty()) return;
        String[] parts = payload.split(",");
        for (String s : parts) {
            try { p.selected.add(Integer.parseInt(s)); } catch (Exception ignored) {}
        }
    }

    public void setReady(int playerIndex, boolean r) { players[playerIndex].ready = r; }
    public boolean bothReady() { return players[0].ready && players[1].ready; }

    public void startMatch() {
        inMatch = true; elapsed = 0;
        units.clear();
        players[0].elixir = 5; players[1].elixir = 5;
        players[0].towerDamage = 0; players[1].towerDamage = 0;
    }
    public boolean inMatch() { return inMatch; }

    public void resetToMenu() {
        inMatch = false;
        players[0].ready = false; players[1].ready = false;
        units.clear();
    }

    // deploy: payload "cardId,localRow,col" where localRow is 0..4 for player's half, col 0..2
    public synchronized void deploy(int playerIndex, String payload) {
        String[] p = payload.split(",");
        if (p.length < 3) return;
        try {
            int cardId = Integer.parseInt(p[0]);
            int localRow = Integer.parseInt(p[1]);
            int col = Integer.parseInt(p[2]);

            // Проверяем корректность локальных координат
            if (localRow < 0 || localRow > 4 || col < 0 || col > 2) return;

            Player pl = players[playerIndex];
            Card c = cards[cardId];
            if (pl.elixir < c.cost) return;

            // Правильное преобразование координат для каждого игрока
            int globalRow;
            if (playerIndex == 0) {
                // Игрок 0 размещает в НИЖНЕЙ половине (строки 5-9)
                globalRow = 5 + localRow; // 5,6,7,8,9
            } else {
                // Игрок 1 размещает в ВЕРХНЕЙ половине (строки 0-4)
                globalRow = 4 - localRow; // 4,3,2,1,0
            }

            // Проверяем, нет ли уже юнита на этой клетке
            for (Unit u : units) {
                if (u.row == globalRow && u.col == col) {
                    return;
                }
            }

            Unit unit = new Unit(cardId, c.hp, playerIndex, globalRow, col);
            units.add(unit);
            pl.elixir -= c.cost;
        } catch (Exception ignored) {}
    }

    public synchronized void advanceTick() {
        if (!inMatch) return;

        // 1) Тайм/эликсир
        elapsed += 3;
        for (Player p : players) {
            if (p.elixir < 10) p.elixir = Math.min(10, p.elixir + 1);
        }

        // Снимок юнитов для безопасной итерации
        List<Unit> snapshot = new ArrayList<>(units);

        // Планирование: для каждого юнита одно действие
        enum ActionType { NONE, ATTACK_UNIT, MOVE, ATTACK_TOWER }
        class Plan { ActionType type = ActionType.NONE; Unit target = null; util.Vec2i dest = null; }
        Map<Unit, Plan> plans = new HashMap<>();

        for (Unit u : snapshot) plans.put(u, new Plan());

        // Фаза 1 — каждый юнит выбирает действие по приоритету
        for (Unit u : snapshot) {
            if (u.hp <= 0) continue;
            Plan p = plans.get(u);
            int owner = u.owner;
            // Направление вперед: для игрока 0 (нижняя половина) - вверх (row уменьшается)
            // для игрока 1 (верхняя половина) - вниз (row увеличивается)
            int forwardDir = (owner == 0) ? -1 : 1;

            // 1) Проверяем врага СЗАДИ (относительно направления движения)
            Unit behind = findAt(u.row - forwardDir, u.col);
            if (behind != null && behind.owner != owner) {
                p.type = ActionType.ATTACK_UNIT;
                p.target = behind;
                continue;
            }

            // 2) Проверяем врага СЛЕВА
            Unit left = findAt(u.row, u.col - 1);
            if (left != null && left.owner != owner) {
                p.type = ActionType.ATTACK_UNIT;
                p.target = left;
                continue;
            }

            // 3) Проверяем врага СПЕРЕДИ
            Unit front = findAt(u.row + forwardDir, u.col);
            if (front != null && front.owner != owner) {
                p.type = ActionType.ATTACK_UNIT;
                p.target = front;
                continue;
            }

            // 4) Если спереди ДРУЖЕСТВЕННЫЙ юнит - пытаемся обойти
            if (front != null && front.owner == owner) {
                boolean canMoveLeft = (u.col > 0) && (findAt(u.row, u.col - 1) == null);
                boolean canMoveRight = (u.col < 2) && (findAt(u.row, u.col + 1) == null);

                // Пытаемся обойти влево (приоритет для обхода)
                if (canMoveLeft) {
                    p.type = ActionType.MOVE;
                    p.dest = new util.Vec2i(u.row, u.col - 1);
                    continue;
                }

                // Если не можем влево, пробуем вправо
                if (canMoveRight) {
                    p.type = ActionType.MOVE;
                    p.dest = new util.Vec2i(u.row, u.col + 1);
                    continue;
                }
                // Если не можем обойти, продолжаем дальше проверять другие действия
            }

            // 5) Если спереди СВОБОДНО - двигаемся вперед
            int newRow = u.row + forwardDir;
            if (newRow >= 0 && newRow <= 9 && findAt(newRow, u.col) == null) {
                p.type = ActionType.MOVE;
                p.dest = new util.Vec2i(newRow, u.col);
                continue;
            }

            // 6) Если стоим у вражеской башни - атакуем её
            if ((owner == 0 && u.row == 0) || (owner == 1 && u.row == 9)) {
                p.type = ActionType.ATTACK_TOWER;
                continue;
            }

            // 7) Если ничего не подошло - стоим на месте
            // (может быть, спереди враг, но мы его уже проверили, или другие условия)
        }

        // Фаза 2 — защитники обязаны отвечать, если на них кто-то нацелился
        Map<Unit, List<Unit>> attackersByTarget = new HashMap<>();
        for (Map.Entry<Unit, Plan> e : plans.entrySet()) {
            Unit attacker = e.getKey();
            Plan pl = e.getValue();
            if (pl.type == ActionType.ATTACK_UNIT && pl.target != null) {
                attackersByTarget.computeIfAbsent(pl.target, k -> new ArrayList<>()).add(attacker);
            }
        }

        // Для каждого защитника, на которого целятся, если он не собирался атаковать — принудим его атаковать
        for (Map.Entry<Unit, List<Unit>> e : attackersByTarget.entrySet()) {
            Unit defender = e.getKey();
            List<Unit> attackers = e.getValue();
            if (defender.hp <= 0) continue;
            Plan defPlan = plans.get(defender);
            if (defPlan == null) continue;
            if (defPlan.type == ActionType.ATTACK_UNIT) continue; // уже планировал атаковать — не трогаем

            // Выбираем атакующего по приоритету defender'а
            int fd = (defender.owner == 0) ? -1 : 1;
            Unit chosen = null;

            // 1) Сначала проверяем врага СЗАДИ (относительно defender'а)
            Unit candBehind = findAt(defender.row - fd, defender.col);
            if (candBehind != null && attackers.contains(candBehind) && candBehind.owner != defender.owner) {
                chosen = candBehind;
            }

            // 2) Если нет врага сзади, проверяем СЛЕВА
            if (chosen == null) {
                Unit candLeft = findAt(defender.row, defender.col - 1);
                if (candLeft != null && attackers.contains(candLeft) && candLeft.owner != defender.owner) {
                    chosen = candLeft;
                }
            }

            // 3) Если нет врага слева, проверяем СПЕРЕДИ
            if (chosen == null) {
                Unit candFront = findAt(defender.row + fd, defender.col);
                if (candFront != null && attackers.contains(candFront) && candFront.owner != defender.owner) {
                    chosen = candFront;
                }
            }

            // 4) Если нет подходящего врага, берем первого из списка
            if (chosen == null && !attackers.isEmpty()) {
                chosen = attackers.get(0);
            }

            if (chosen != null) {
                defPlan.type = ActionType.ATTACK_UNIT;
                defPlan.target = chosen;
            }
        }

        // Фаза 3 — сбор урона: все атакующие наносят урон своим целям
        Map<Unit, Integer> damageTaken = new HashMap<>();
        for (Map.Entry<Unit, Plan> e : plans.entrySet()) {
            Unit who = e.getKey();
            Plan pl = e.getValue();
            if (who.hp <= 0) continue;
            if (pl.type == ActionType.ATTACK_UNIT && pl.target != null) {
                Unit tgt = pl.target;
                int damage = cards[who.id].atk;
                damageTaken.put(tgt, damageTaken.getOrDefault(tgt, 0) + damage);
                // Отладка
                System.out.println("[SERVER] Unit " + who.id + " (owner " + who.owner + ") attacks " +
                        tgt.id + " (owner " + tgt.owner + ") for " + damage + " damage");
            }
        }

        // Фаза 4 — применяем урон одновременно
        for (Map.Entry<Unit, Integer> e : damageTaken.entrySet()) {
            Unit target = e.getKey();
            int dmg = e.getValue();
            target.hp -= dmg;
            System.out.println("[SERVER] Unit " + target.id + " (owner " + target.owner +
                    ") at " + target.row + "," + target.col +
                    " took " + dmg + " damage, HP now " + target.hp);
        }

        // Фаза 5 — удаляем мёртвых
        units.removeIf(u -> u.hp <= 0);
        System.out.println("[SERVER] After removing dead units, total units: " + units.size());

        // Фаза 6 — применяем движения
        Map<String, List<Unit>> destToUnits = new HashMap<>();
        for (Map.Entry<Unit, Plan> e : plans.entrySet()) {
            Unit u = e.getKey();
            Plan pl = e.getValue();
            if (u.hp <= 0) continue;
            if (pl.type == ActionType.MOVE && pl.dest != null) {
                util.Vec2i d = pl.dest;
                // Проверяем, что внутри поля
                if (d.x < 0 || d.x > 9 || d.y < 0 || d.y > 2) continue;
                // Если клетка занята после удаления мертвых - отменяем движение
                if (findAt(d.x, d.y) != null) continue;
                String key = d.x + ":" + d.y;
                destToUnits.computeIfAbsent(key, k -> new ArrayList<>()).add(u);
            }
        }

        // Выполняем только уникальные ходы (по 1 юниту на клетку)
        for (Map.Entry<String, List<Unit>> de : destToUnits.entrySet()) {
            List<Unit> want = de.getValue();
            if (want.size() == 1) {
                Unit mover = want.get(0);
                Plan pl = plans.get(mover);
                if (mover.hp > 0 && pl != null && pl.dest != null) {
                    // Двойная проверка на занятость
                    if (findAt(pl.dest.x, pl.dest.y) == null) {
                        System.out.println("[SERVER] Unit " + mover.id + " (owner " + mover.owner +
                                ") moves to " + pl.dest.x + "," + pl.dest.y);
                        mover.row = pl.dest.x;
                        mover.col = pl.dest.y;
                    }
                }
            }
        }

        // Фаза 7 — атака башен
        for (Map.Entry<Unit, Plan> e : plans.entrySet()) {
            Unit u = e.getKey();
            Plan pl = e.getValue();
            if (u.hp <= 0) continue;
            if (pl.type == ActionType.ATTACK_TOWER) {
                if (u.owner == 0 && u.row == 0) {
                    players[1].towerDamage += cards[u.id].atk;
                    System.out.println("[SERVER] Player 0 unit " + u.id + " attacks tower of player 1");
                } else if (u.owner == 1 && u.row == 9) {
                    players[0].towerDamage += cards[u.id].atk;
                    System.out.println("[SERVER] Player 1 unit " + u.id + " attacks tower of player 0");
                }
            }
        }

        System.out.println("[SERVER] Tick complete. Elixir: " + players[0].elixir + "/" + players[1].elixir +
                ", Tower damage: " + players[0].towerDamage + "/" + players[1].towerDamage);
    }


    private Unit findAt(int row, int col) {
        if (row < 0 || row > 9 || col < 0 || col > 2) return null;
        for (Unit u : units)
            if (u.row == row && u.col == col) return u;
        return null;
    }

    private Unit findByRef(Unit sample) {
        for (Unit u : units)
            if (u == sample) return u;
        return null;
    }

    public boolean isMatchOver() {
        return elapsed >= matchTimeSeconds;
    }

    public String computeResult() {
        return players[0].towerDamage + "/" + players[1].towerDamage;
    }

    public String serializeForClients() {
        StringBuilder sb = new StringBuilder();
        sb.append(players[0].elixir).append('|').append(players[1].elixir).append('|');
        sb.append(players[0].towerDamage).append('|').append(players[1].towerDamage).append('|');
        for (Unit u : units) {
            sb.append(u.owner).append(',')
                    .append(u.id).append(',')
                    .append(u.row).append(',')
                    .append(u.col).append(',')
                    .append(u.hp).append(';');
        }
        return sb.toString();
    }
}