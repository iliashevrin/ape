package com.android.commands.monkey.ape.agent;

import java.util.Set;

public class DiffBasedAgent extends StatefulAgent {

    String logFile;

    Set<String> focusActivities = new HashSet<>();
    Set<String> allActivities = new HashSet<>();

    // Activity -> Activity -> Set of Actions
    private static Map<String, Map<String, Set<ActionFromLog>>> graph = new HashMap<>();

    public static class ActionFromLog {
        ActionType actionType;
        String targetXpath;

        ActionFromLog(ActionType actionType, String xpath) {
            this.actionType = actionType;
            this.targetXpath = xpath;
        }
    }

    public DiffBasedAgent(MonkeySourceApe ape, Graph graph, String previousLog) {
        super(ape, graph);
        this.logFile = previousLog;

        buildATGFromLog(previousLog);
    }


    private static void buildATGFromLog(String previousLog) {

        BufferedReader reader;

        try {
            reader = new BufferedReader(new FileReader(previousLog));
            String line = readLine(reader);

            while (line != null) {

                if (line.contains("=== Adding edge...")) {

                    while (!line.contains("Source: ")) {
                        line = readLine(reader);
                    }
                    String source = parseActivity(line);

                    if (source.endsWith("Activity") && !graph.containsKey(source)) {
                        graph.put(source, new HashMap<>());
                    }

                    while (!line.contains("Action: ")) {
                        line = readLine(reader);
                    }
                    ActionType actionType = parseActionType(line);
                    String xpath = parseTargetXpath(line);

                    while (!line.contains("Target: ")) {
                        line = readLine(reader);
                    }
                    String target = parseActivity(line);


                    // Also add target as node in graph
                    if (target.endsWith("Activity") && !graph.containsKey(target)) {
                        graph.put(target, new HashMap<>());
                    }

                    if (!graph.get(source).containsKey(target)) {
                        graph.get(source).put(target, new ArrayList<>());
                    }

                    graph.get(source).get(target).add(new ActionFromLog(actionType, xpath));
                }

                line = readLine(reader);

            }


            reader.close();

        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static String readLine(BufferedReader reader) throws IOException {

        String line = null;
        do {
            line = cleanLine(reader.readLine());
        } while (line != null && "".equals(line));

        return line;
    }

    private static String parseTargetXpath(String line) {

        String temp = line.split("@")[1];

        // Use regex

        temp = temp.substring(0, temp.indexOf("["));

        Pattern p = Pattern.compile(".*class=([^;]*);(resource-id=([^;]*);)?(.*);");

        Matcher m = p.matcher(temp);

        if (m.matches()) {

            String cls = m.group(1);
            String resourceId = m.group(3) == null ? "" : m.group(3);
            String[] props = m.group(4).split(";");

            String target = String.format("//*[@class=\"%s\"][@resource-id=\"%s\"]", cls, resourceId);

            for (String prop : props) {

                String propName = prop.split("=")[0];
                String value = prop.split("=")[1];
                target += String.format("[@%s='%s']", propName, value);
            }
            return target;
        }

        return null;

    }

    private static String cleanLine(String line) {
        if (line == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < line.length(); i++) {
            if (line.charAt(i) != 0) {
                sb.append((char) line.charAt(i));
            }
        }
        return sb.toString();
    }

    private static String parseActivity(String line) {
        String temp = line.split("@")[0];
        return temp.substring(temp.lastIndexOf("]") + 1);
    }

    private static ActionType parseActionType(String line) {
        String temp = line.split("@")[1];

        for (ActionType type : ActionType.values()) {
            if (temp.contains(type.toString())) {
                return type;
            }
        }
        return null;
    }

}