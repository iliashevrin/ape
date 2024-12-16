package com.android.commands.monkey.ape.agent;

import java.util.Set;

public class DiffBasedAgent extends StatefulAgent {

    String logFile;

    Set<String> focusActivities = new HashSet<>();
    Set<String> allActivities = new HashSet<>();

    Mode mode = Mode.REACHING;

    List<String> reachingPath = new ArrayList<>();

    // Activity -> Activity -> Set of Actions
    private Map<String, Map<String, Set<ActionFromLog>>> graph = new HashMap<>();

    public static class ActionFromLog {
        ActionType actionType;
        String targetXpath;

        ActionFromLog(ActionType actionType, String xpath) {
            this.actionType = actionType;
            this.targetXpath = xpath;
        }
    }


    public static enum Mode {
        REACHING,
        EXPLORING;
    }

    public DiffBasedAgent(MonkeySourceApe ape, Graph graph, String previousLog, String manifestFile, String focusSet) {
        super(ape, graph);
        this.logFile = previousLog;

        buildATGFromLog(previousLog);
        parseFocusSet(focusSet);

        this.reachingPath = nextPath(focusActivities);


    }

    private void parseFocusSet(String focusSet) {
        this.focusActivities = new HashSet<>(Arrays.asList(focusSet.substring(1, focusSet.length()-1).split(",")));
    }


    private void buildATGFromLog(String previousLog) {

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


    /*
    Copy from ReplayAgent.java
    Should we extend the class?
     */

    protected Name resolveName(NodeList nodeList) {
        int index = RandomHelper.nextInt(nodeList.getLength());
        Element e = (Element) nodeList.item(index);
        GUITreeNode node = GUITreeBuilder.getGUITreeNode(e);
        if (node == null) {
            return null;
        }
        return node.getXPathName();
    }

    protected Name resolveName(String target) throws XPathExpressionException {
        int retry = 3;
        while (retry--> 0) {
            GUITree guiTree = newState.getLatestGUITree();
            Document guiXml = guiTree.getDocument();
            XPathExpression targetXPath = XPathBuilder.compileAbortOnError(target);
            NodeList nodesByTarget = (NodeList) targetXPath.evaluate(guiXml, XPathConstants.NODESET);
            Name name;
            if (nodesByTarget.getLength() != 0) {
                name = resolveName(nodesByTarget);
            } else {
                refreshNewState();
                continue;
            }
            if (name != null) {
                return name;
            }
        }
        Logger.wprintln("Cannot resolve node.");
        return null;
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


    // Finds next path to nearest activity in focus set that was not yet traversed
    private List<String> nextPath(Set<String> focusSet) {

        Queue<List<String>> q = new LinkedList<>();
        Set<String> visited = new HashSet<>();
        q.add(Collections.singletonList(getMain()));

        while (!q.isEmpty()) {

            List<String> curr = q.poll();
            String lastActivity = curr.getLast();
            visited.add(lastActivity);

            if (focusSet.contains(lastActivity)) {
                return curr;
            }

            for (String next : graph.get(lastActivity).keySet()) {
                if (!visited.contains(next)) {
                    List<String> newPath = new ArrayList<>(curr);
                    newPath.add(next);
                    q.add(newPath);
                }
            }
        }

        return null;
    }


    @Override
    protected Action selectNewActionNonnull() {

        String newActivity = newState.getActivity();

        if (mode == Mode.REACHING) {

            if (reachingPath.getLast().equals(newActivity) {
                mode = Mode.EXPLORING;
            } else {

                String wantedActivity = reachingPath.get(reachingPath.indexOf(newActivity) + 1);

                Set<ActionFromLog> possibleActions = graph.get(newActivity).get(wantedActivity);

                for (ActionFromLog action : possibleActions) {
                    Name name = resolveName(action.targetXpath);
                    ModelAction action = newState.getAction(name, action.actionType);

                    if (action != null) {
                        return action;
                    }
                }

                // All actions towards current focus activity didn't work
                return handleNullAction()
            }

        } else if (mode == Mode.EXPLORING) {

            if (!reachingPath.getLast().equals(newActivity) {
                return newState.getBackAction();
            } else {

            }
        }

        return null;
    }

    @Override
    public void onBufferLoss(State actual, State expected) {
        throw new RuntimeException("Not implemented");
    }

    @Override
    public void onRefillBuffer(Subsequence path) {
        throw new RuntimeException("Not implemented");
    }
}