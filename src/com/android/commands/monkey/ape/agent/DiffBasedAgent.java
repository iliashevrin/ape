package com.android.commands.monkey.ape.agent;

import javax.xml.transform.*;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.util.*;
import java.io.*;
import com.android.commands.monkey.ape.*;
import com.android.commands.monkey.ape.model.*;
import com.android.commands.monkey.ape.agent.StatefulAgent;
import com.android.commands.monkey.ape.naming.Name;
import com.android.commands.monkey.ape.tree.GUITree;
import com.android.commands.monkey.ape.tree.GUITreeBuilder;
import com.android.commands.monkey.ape.tree.GUITreeNode;
import com.android.commands.monkey.ape.utils.XPathBuilder;
import com.android.commands.monkey.ape.utils.RandomHelper;
import com.android.commands.monkey.MonkeySourceApe;
import com.android.commands.monkey.ape.utils.Logger;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathFactory;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpression;
import javax.xml.xpath.XPathExpressionException;

import org.json.JSONException;
import org.json.JSONObject;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import android.content.ComponentName;

public class DiffBasedAgent extends StatefulAgent {

    private String logFile;

    private Set<String> focusActivities = new HashSet<>();
    private Set<String> allActivities = new HashSet<>();
    private Set<String> erroneousActivities = new HashSet<>();
    private Set<State> exploringStates = new HashSet<>();

    private Mode mode = Mode.INITIALIZING;

    private List<String> reachingPath = new ArrayList<>();

    private Map<String, Map<String, Set<ActionFromLog>>> newEdges = new HashMap<>();

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

        INITIALIZING,
        REACHING,
        EXPLORING;
    }

    public DiffBasedAgent(MonkeySourceApe ape, Graph graph, String previousLog, String manifestFile, String focusSet) {
        super(ape, graph);
        this.logFile = previousLog;

        parseFocusSet(focusSet);

        if (focusActivities.isEmpty()) {
            Logger.println("No focus activities, can stop execution");
        } else {
            buildATGFromLog(previousLog);

            try {
                Document manifest = parseManifest(manifestFile);
                this.allActivities = getActivities(manifest);
            } catch (Exception e) {
                e.printStackTrace();
                throw new StopTestingException("Unable to parse manifest XML file");
            }
        }

    }

    private void parseFocusSet(String focusSet) {
        this.focusActivities = new HashSet<>(Arrays.asList(focusSet.split(",")));
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
                        graph.put(source, new HashMap<String, Set<ActionFromLog>>());
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
                        graph.put(target, new HashMap<String, Set<ActionFromLog>>());
                    }

                    if (!graph.get(source).containsKey(target)) {
                        graph.get(source).put(target, new HashSet<ActionFromLog>());
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

    public static String documentToString(Document document) throws TransformerException {
        TransformerFactory transformerFactory = TransformerFactory.newInstance();
        Transformer transformer = transformerFactory.newTransformer();
        StringWriter stringWriter = new StringWriter();
        transformer.transform(new DOMSource(document), new StreamResult(stringWriter));
        return stringWriter.toString();
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

    protected Name resolveName(String target) throws XPathExpressionException, TransformerException {
        int retry = 2;
        while (retry--> 0) {
            GUITree guiTree = newState.getLatestGUITree();
            Document guiXml = guiTree.getDocument();
            Logger.println(documentToString(guiXml));
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



    private static Document parseManifest(String manifestXml) throws SAXException, IOException, ParserConfigurationException {

        File manifest = new File(manifestXml);

        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
//        factory.setValidating(true);
        factory.setIgnoringElementContentWhitespace(true);
        DocumentBuilder builder = factory.newDocumentBuilder();

        return builder.parse(manifest);
    }


    private static Set<String> getActivities(Document manifest) throws ParserConfigurationException, IOException, SAXException, XPathExpressionException {

        Set<String> activities = new HashSet<>();

        XPath xPath = XPathFactory.newInstance().newXPath();
        NodeList nodeList = (NodeList) xPath.compile("//activity").evaluate(manifest, XPathConstants.NODESET);
        for (int i = 0; i < nodeList.getLength(); i++) {
            activities.add(nodeList.item(i).getAttributes().getNamedItem("android:name").getNodeValue());
        }
        return activities;
    }



    private String getMain() {
        for (String activity : graph.keySet()) {
            if (activity.contains("MainActivity")) {
                return activity;
            }
        }

        return null;
    }


    // Finds next path to nearest activity in focus set that was not yet traversed
    private List<String> nextPath(String source) {

        Queue<List<String>> q = new LinkedList<>();
        Set<String> visited = new HashSet<>();
//        q.add(Collections.singletonList(getMain()));
        q.add(Collections.singletonList(source));

        while (!q.isEmpty()) {

            List<String> curr = q.poll();
            String lastActivity = curr.get(curr.size()-1);
            visited.add(lastActivity);

            // Find a path towards a focus activity that is not erroneous
            if (focusActivities.contains(lastActivity) && !erroneousActivities.contains(lastActivity)) {
                Logger.format("New activity to reach is %s", lastActivity);
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

    private ModelAction getActionFromPath(String newActivity) {

        if (!reachingPath.contains(newActivity)) {
            return null;
        }

        String wantedActivity = reachingPath.get(reachingPath.indexOf(newActivity) + 1);

        Set<ActionFromLog> possibleActions = graph.get(newActivity).get(wantedActivity);

        // Should never happen
        if (possibleActions == null) {
            return null;
        }

        Logger.format("New activity=%s; Wanted activity=%s; #actions=%d",
                newActivity, wantedActivity, possibleActions.size());

        for (ActionFromLog actionFromLog : possibleActions) {
            try {
                Logger.format("New activity=%s; Wanted activity=%s; Action=%s; Xpath=%s",
                        newActivity, wantedActivity, actionFromLog.actionType, actionFromLog.targetXpath);
                Name name = resolveName(actionFromLog.targetXpath);

                if (name != null) {
                    ModelAction action = newState.getAction(name, actionFromLog.actionType);
                    Logger.format("Found action=%s", action);
                    if (action != null) {
                        return action;
                    }
                }

            } catch (Exception e) {
                e.printStackTrace();
                // Check next action
            }
        }

        return null;
    }

    private Action reachingModeAction(String newActivity, String target) {

        ModelAction action = getActionFromPath(newActivity);

        // All actions towards current focus activity didn't work
        // Maybe it will be possible to reach it via other changed activities
        if (action == null) {
            erroneousActivities.add(target);
            return findNextPath(newActivity);
        }

        return action;
    }

    private Action findNextPath(String source) {

        List<String> path = nextPath(source);

        if (path != null) {
            mode = Mode.REACHING;
            this.reachingPath = path;
            String target = reachingPath.get(reachingPath.size() - 1);
            return reachingModeAction(source, target);
        }

        return getStartAction(nextRestartAction());
    }


    @Override
    protected Action selectNewActionNonnull() {

        String newActivity = newState.getActivity();
        String target = reachingPath.get(reachingPath.size() - 1);

        if (target.equals(newActivity)) {
            mode = Mode.EXPLORING;
        }

        if (mode == Mode.INITIALIZING) {
            return findNextPath(newActivity);
        } else if (mode == Mode.REACHING) {
            return reachingModeAction(newActivity, target);
        } else if (mode == Mode.EXPLORING) {

            // If spotted an activity not existing in previous version, add it to focus
            if (!allActivities.contains(newActivity)) {
                focusActivities.add(newActivity);
            }

            if (currentState != null) {
                String currentActivity = currentState.getActivity();

                // Update as new edgess only activities from focus set or new
                if (focusActivities.contains(currentActivity)) {

                    if (!newEdges.containsKey(currentActivity)) {
                        newEdges.put(currentActivity, new HashMap<String, Set<ActionFromLog>>());
                    }
                    if (!newEdges.get(currentActivity).containsKey(newActivity)) {
                        newEdges.get(currentActivity).put(newActivity, new HashSet<ActionFromLog>());
                    }
                    String xpath = parseTargetXpath(currentAction.toString());
                    newEdges.get(currentActivity).get(newActivity).add(new ActionFromLog(currentAction.getType(), xpath));
                }
            }

            // If not in focus, go back
            if (!focusActivities.contains(newActivity)) {
                return newState.getBackAction();
            } else {

                exploringStates.add(newState);

                Logger.format("Exploring %s, total actions to explore %d", newActivity, newState.targetedActions().size());

                for (ModelAction action : newState.targetedActions()) {
                    if (!action.isVisited()) {
                        return action;
                    }
                }
                // If exhausted the activity, remove from focusActivities set and update graph
                focusActivities.remove(newActivity);
                exploringStates.remove(newState);

                graph.put(newActivity, newEdges.get(newActivity));

                // Check if can return to another focus activity that is currently being explored through visited actions
                for (State state : exploringStates) {
                    for (StateTransition st : getGraph().getInStateTransitions(newState)) {
                        if (st.getTarget().equals(state) && !state.getActivity().equals(newActivity)) {
                            return st.getAction();
                        }
                    }
                }

                // Choose a new focus action and start again
                // Should clear exploringStates?
                if (!focusActivities.isEmpty()) {
                    return findNextPath(newActivity);
                }

                Logger.println("No more focus activities, can stop execution");
            }
        }

        Logger.println("Null action");

        // Finished the dynamic analysis
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

    @Override
    public String getLoggerName() {
        return "DiffBased";
    }

    @Override
    public void onBadState(int lastBadStateCount, int badStateCounter) {
    }

    @Override
    public boolean onVoidGUITree(int counter) {
        return false;
    }

    @Override
    public void onActivityBlocked(ComponentName blockedActivity) {
    }
}