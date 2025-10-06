package com.example;

import com.example.api.ElpriserAPI;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;

public class Main {
    public static void main(String[] args) {
        boolean showHelp = false;
        String zone = null;
        String dateStr = null;
        boolean sorted = false;
        String charging = null;

        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--help")) showHelp = true;
            else if (args[i].equals("--zone") && i + 1 < args.length) zone = args[++i];
            else if (args[i].equals("--date") && i + 1 < args.length) dateStr = args[++i];
            else if (args[i].equals("--sorted")) sorted = true;
            else if (args[i].equals("--charging") && i + 1 < args.length) charging = args[++i];
        }

        if (showHelp || args.length == 0) {
            printUsage();
            return;
        }

        if (zone == null || !(zone.equals("SE1") || zone.equals("SE2") || zone.equals("SE3") || zone.equals("SE4"))) {
            System.out.println("Fel zon: du måste ange --zone SE1|SE2|SE3|SE4");
            return;
        }

        LocalDate date;
        if (dateStr == null) {
            date = LocalDate.now();
        } else {
            try {
                date = LocalDate.parse(dateStr, DateTimeFormatter.ISO_LOCAL_DATE);
            } catch (DateTimeParseException e) {
                System.out.println("Fel: ogiltigt datum");
                return;
            }
        }

        ElpriserAPI api = new ElpriserAPI(false);
        List<ElpriserAPI.Elpris> prices = api.getPriser(date, ElpriserAPI.Prisklass.valueOf(zone));

        if (charging != null || sorted) {
            List<ElpriserAPI.Elpris> nextDay = api.getPriser(date.plusDays(1), ElpriserAPI.Prisklass.valueOf(zone));
            if (!nextDay.isEmpty()) {
                prices = new ArrayList<>(prices);
                prices.addAll(nextDay);
            } else if (charging != null) {
                System.out.println("Imorgon's priserna är inte tillgängliga, bara idag's priserna");
            }
        }

        if (prices.isEmpty()) {
            System.out.println("Inga priser tillgängliga för valda datum/zonen.");
            return;
        }

        boolean isQuarter = prices.size() == 96;
        List<ElpriserAPI.Elpris> hourly = isQuarter ? convertQuartToHour(prices) : prices;

        double total = 0;
        double minValue = Double.MAX_VALUE;
        double maxValue = Double.MIN_VALUE;
        ElpriserAPI.Elpris minPrice = null;
        ElpriserAPI.Elpris maxPrice = null;

        for (ElpriserAPI.Elpris p : hourly) {
            double price = p.sekPerKWh();
            total += price;
            if (price < minValue) {
                minValue = price;
                minPrice = p;
            }
            if (price > maxValue) {
                maxValue = price;
                maxPrice = p;
            }
        }

        double mean = total / hourly.size();
        System.out.println("Medelpris: " + formatOre(mean) + " öre");
        System.out.println("Lägsta pris: " + formatOre(minValue) + " (" + formatHour(minPrice.timeStart().getHour()) + ")");
        System.out.println("Högsta pris: " + formatOre(maxValue) + " (" + formatHour(maxPrice.timeStart().getHour()) + ")");

        if (sorted) {
            List<ElpriserAPI.Elpris> sortedList = new ArrayList<>(hourly);
            Collections.sort(sortedList, new Comparator<ElpriserAPI.Elpris>() {
                public int compare(ElpriserAPI.Elpris p1, ElpriserAPI.Elpris p2) {
                    int cmp = Double.compare(p2.sekPerKWh(), p1.sekPerKWh());
                    if (cmp != 0) return cmp;
                    return p1.timeStart().compareTo(p2.timeStart());
                }
            });

            for (ElpriserAPI.Elpris p : sortedList) {
                int hour = p.timeStart().getHour();
                System.out.println(formatHour(hour) + " " + formatOre(p.sekPerKWh()) + " öre");
            }
        }

        if (charging != null) {
            int hoursNeeded = Integer.parseInt(charging.replace("h", ""));
            if (prices.size() < hoursNeeded) {
                System.out.println("Inte tillräckligt med data för ett " + hoursNeeded + "h fönster.");
                return;
            }

            double minSum = Double.MAX_VALUE;
            int startIdx = -1;

            for (int i = 0; i <= prices.size() - hoursNeeded; i++) {
                double sum = 0;
                for (int j = 0; j < hoursNeeded; j++) {
                    sum += prices.get(i + j).sekPerKWh();
                }
                if (sum < minSum || (sum == minSum && i < startIdx)) {
                    minSum = sum;
                    startIdx = i;
                }
            }

            int startHour = prices.get(startIdx).timeStart().getHour();
            double avgWindow = minSum / hoursNeeded;
            System.out.println("Påbörja laddning: kl " + String.format("%02d:00", startHour));
            System.out.println("Medelpris för fönster: " + formatOre(avgWindow) + " öre");
        }
    }

    private static List<ElpriserAPI.Elpris> convertQuartToHour(List<ElpriserAPI.Elpris> quarters) {
        List<ElpriserAPI.Elpris> hourly = new ArrayList<>();
        for (int i = 0; i < 24; i++) {
            double sum = 0;
            for (int j = 0; j < 4; j++) {
                sum += quarters.get(i * 4 + j).sekPerKWh();
            }
            double avg = sum / 4;
            ElpriserAPI.Elpris base = quarters.get(i * 4);
            hourly.add(new ElpriserAPI.Elpris(avg, 0.0, 0.0, base.timeStart(), base.timeEnd()));
        }
        return hourly;
    }

    private static void printUsage() {
        System.out.println("Usage: java -jar elpriser.jar --zone SE1|SE2|SE3|SE4 [--date YYYY-MM-DD] [--charging Nh] [--sorted]");
    }

    private static String formatOre(double sekPerKWh) {
        return String.format(Locale.forLanguageTag("sv-SE"), "%.2f", sekPerKWh * 100);
    }

    private static String formatHour(int h) {
        return String.format("%02d-%02d", h, (h + 1) % 24);
    }
}
