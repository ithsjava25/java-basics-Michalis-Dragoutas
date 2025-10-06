package com.example;

import com.example.api.ElpriserAPI;
import com.example.api.ElpriserAPI.Elpris;
import com.example.api.ElpriserAPI.Prisklass;


import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.ArrayList;
import java.util.List;

public class Main {
    public static void main(String[] args) {
        ElpriserAPI elpriserAPI = new ElpriserAPI();

        if (args.length == 0 || Arrays.asList(args).contains("--help")) {
            printHelp();
            return;
        }

        String zoneArg = null;
        String dateArg = null;
        boolean sorted = false;
        String chargingArg = null;

        // Parse arguments
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--zone" -> {
                    if (i + 1 < args.length) zoneArg = args[++i];
                }
                case "--date" -> {
                    if (i + 1 < args.length) dateArg = args[++i];
                }
                case "--sorted" -> sorted = true;
                case "--charging" -> {
                    if (i + 1 < args.length) chargingArg = args[++i];
                }
            }
        }

        // Validate zone
        if (zoneArg == null) {
            System.out.println("Missing required argument: --zone");
            return;
        }
        Prisklass zone;
        try {
            zone = Prisklass.valueOf(zoneArg);
        } catch (Exception e) {
            System.out.println("Invalid zone: " + zoneArg + ". Valid zones: SE1, SE2, SE3, SE4");
            return;
        }

        // Validate date
        LocalDate date;
        if (dateArg == null) {
            date = LocalDate.now();
        } else {
            try {
                date = LocalDate.parse(dateArg, DateTimeFormatter.ISO_LOCAL_DATE);
            } catch (DateTimeParseException e) {
                System.out.println("Invalid date: " + dateArg + ". Use format YYYY-MM-DD.");
                return;
            }
        }

        ElpriserAPI api = new ElpriserAPI(false); // disable caching for tests
        List<Elpris> todayPrices = api.getPriser(date, zone);

        // If charging requested, also fetch next day
        List<Elpris> allPrices = new ArrayList<>(todayPrices);
        if (chargingArg != null) {
            allPrices.addAll(api.getPriser(date.plusDays(1), zone));
        }

        if (allPrices.isEmpty()) {
            System.out.println("No data available for " + zone + " on " + date);
            return;
        }

        // Sorted output
        if (sorted) {
            printSorted(allPrices);
        }

        // Min, Max, Mean
        printPrices(todayPrices);

        // Charging window
        if (chargingArg != null) {
            handleCharging(allPrices, chargingArg);
        }
    }

    private static void printHelp() {
        System.out.println("Usage: java Main --zone ZONE [--date YYYY-MM-DD] [--sorted] [--charging Nh]");
        System.out.println("Options:");
        System.out.println("  --zone ZONE       Required. One of SE1, SE2, SE3, SE4");
        System.out.println("  --date DATE       Optional. Default is today.");
        System.out.println("  --sorted          Optional. Show all prices sorted ascending.");
        System.out.println("  --charging Nh     Optional. Find cheapest charging window (e.g. 2h, 4h, 8h).");
        System.out.println("  --help            Show this help message.");
    }

    private static void printSorted(List<Elpris> prices) {
        prices.stream()
                .sorted(Comparator.comparingDouble(Elpris::sekPerKWh).reversed())
                .forEach(p -> {
                    String date = p.timeStart().toLocalDate().toString();
                    String span = String.format("%02d-%02d", p.timeStart().getHour(), (p.timeStart().getHour() + 1) % 24);
                    String price = formatOre(p.sekPerKWh());
                    // exakt spacing som MainTest förväntar sig
                    System.out.println(String.format("%-12s %-11s %10s öre", date, span, price));
                });
    }

    private static void printPrices(List<Elpris> prices) {
        if (prices.isEmpty()) return;

        double[] hourSums = new double[24];
        int[] hourCounts = new int[24];

        for (Elpris p : prices) {
            int hour = p.timeStart().getHour();
            hourSums[hour] += p.sekPerKWh();
            hourCounts[hour]++;
        }

        double minAvg = Double.MAX_VALUE;
        double maxAvg = Double.MIN_VALUE;
        int minHour = 0;
        int maxHour = 0;

        for (int h = 0; h < 24; h++) {
            if (hourCounts[h] > 0) {
                double avg = hourSums[h] / hourCounts[h];
                if (avg < minAvg) {
                    minAvg = avg;
                    minHour = h;
                }
                if (avg > maxAvg) {
                    maxAvg = avg;
                    maxHour = h;
                }
            }
        }

        double overallAvg = prices.stream().mapToDouble(Elpris::sekPerKWh).average().orElse(0);

        System.out.println("Lägsta pris: " + String.format("%02d-%02d", minHour, (minHour + 1) % 24) + " " + formatOre(minAvg) + " öre");
        System.out.println("Högsta pris: " + String.format("%02d-%02d", maxHour, (maxHour + 1) % 24) + " " + formatOre(maxAvg) + " öre");
        System.out.println("Medelpris: " + formatOre(overallAvg) + " öre");
    }

    private static void handleCharging(List<Elpris> prices, String chargingArg) {
        int hours = Integer.parseInt(chargingArg.replace("h", ""));
        if (prices.size() < hours ) {
            System.out.println("Not enough data for charging window.");
            return;
        }

        double bestAvg = Double.MAX_VALUE;
        int bestIndex = 0;
        for (int i = 0; i <= prices.size() - hours; i++) {
            double avg = prices.subList(i, i + hours).stream().mapToDouble(Elpris::sekPerKWh).average().orElse(0);
            if (avg < bestAvg) {
                bestAvg = avg;
                bestIndex = i;
            }
        }

        Elpris start = prices.get(bestIndex);
        System.out.println("Påbörja laddning kl " + String.format("%02d:00", start.timeStart().getHour()));
        System.out.println("Medelpris för fönster: " + formatOre(bestAvg) + " öre");
    }

    private static String formatOre(double sekPerKWh) {
        return String.format(Locale.forLanguageTag("sv-SE"), "%.2f", sekPerKWh * 100);
    }
}