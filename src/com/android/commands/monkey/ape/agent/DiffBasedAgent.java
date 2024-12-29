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
import org.w3c.dom.Node;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import android.content.ComponentName;

public class DiffBasedAgent extends StatefulAgent {

    private String logFile;
    private String namespace;

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
                this.namespace = getNamespace(manifest);
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
//            Logger.println(documentToString(guiXml));
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

    private static String getNamespace(Document manifest) throws ParserConfigurationException, IOException, SAXException, XPathExpressionException {

        XPath xPath = XPathFactory.newInstance().newXPath();
        Node node = (Node) xPath.compile("//application").evaluate(manifest, XPathConstants.NODE);
        Node namespaceNode = node.getAttributes().getNamedItem("android:name");
        if (namespaceNode != null) {
            String namespace = namespaceNode.getNodeValue();
            namespace = namespace.replace('.', '/').replace('"', ' ').trim();
            return namespace.substring(0, namespace.lastIndexOf('/'));
        }
        else {
            return "";
        }
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

    private String getExpectedNext(String source) {
        return reachingPath.get(reachingPath.indexOf(source) + 1);
    }

    private ModelAction getActionFromPath(String source) {

        // Should never happen
        if (!reachingPath.contains(source)) {
            return null;
        }

        String next = getExpectedNext(source);

        Set<ActionFromLog> possibleActions = graph.get(source).get(next);

        // Should never happen
        if (possibleActions == null) {
            return null;
        }

        Logger.format("Source=%s; Next=%s; #actions=%d",
                source, next, possibleActions.size());

        for (ActionFromLog actionFromLog : possibleActions) {
            try {
                Logger.format("Source=%s; Next=%s; Action=%s; Xpath=%s",
                        source, next, actionFromLog.actionType, actionFromLog.targetXpath);
                Name name = resolveName(actionFromLog.targetXpath);

                if (name != null) {
                    ModelAction action = newState.getAction(name, actionFromLog.actionType);
                    Logger.format("Found action=%s", action);

                    if (action != null && !action.isVisited()) {
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

    private Action reachingModeAction(String source, String target) {

        ModelAction action = getActionFromPath(source);

        // All actions towards current focus activity didn't work
        // Maybe it will be possible to reach it via other changed activities
        if (action == null) {
            Logger.format("No viable action from source %s to target activity %s", source, target);
            erroneousActivities.add(target);
            return findNextPath(source);
        }

        return action;
    }

    private Action findNextPath(String source) {

        List<String> path = nextPath(source);

        if (path != null) {
            mode = Mode.REACHING;
            this.reachingPath = path;
            String target = reachingPath.get(reachingPath.size() - 1);
            Logger.format("Found next path from source activity %s to new target activity %s", source, target);

            if (source.equals(target)) {
                mode = mode.EXPLORING;
                return exploringModeAction(source);
            }

            return reachingModeAction(source, target);
        }

        Logger.format("Could not find path from source activity %s to any target, restarting", source);
        mode = Mode.INITIALIZING;

        return getStartAction(nextRestartAction());
    }

    private Action exploringModeAction(String source) {
        // If spotted an activity not existing in previous version, add it to focus
        if (!allActivities.contains(source)) {
            focusActivities.add(source);
        }

        if (currentState != null) {
            String previous = currentState.getActivity();

            // Update as new edges only activities from focus set or new
            if (focusActivities.contains(previous)) {

                if (!newEdges.containsKey(previous)) {
                    newEdges.put(previous, new HashMap<String, Set<ActionFromLog>>());
                }
                if (!newEdges.get(previous).containsKey(source)) {
                    newEdges.get(previous).put(source, new HashSet<ActionFromLog>());
                }
                String xpath = parseTargetXpath(currentAction.toString());
                newEdges.get(previous).get(source).add(new ActionFromLog(currentAction.getType(), xpath));
            }
        }

        // If not in focus, go back
        if (!focusActivities.contains(source)) {
            return newState.getBackAction();
        } else {

            exploringStates.add(newState);

            Logger.format("Exploring activity %s, total actions to explore %d", source, newState.targetedActions().size());

            for (ModelAction action : newState.targetedActions()) {
                if (!action.isVisited()) {
                    return action;
                }
            }
            // If exhausted the activity, remove from focusActivities set and update graph
            focusActivities.remove(source);
            exploringStates.remove(newState);

            graph.put(source, newEdges.get(source));

            // Check if can return to another focus activity that is currently being explored through visited actions
            for (State state : exploringStates) {
                for (StateTransition st : getGraph().getInStateTransitions(newState)) {
                    if (st.getTarget().equals(state) && !state.getActivity().equals(source)) {
                        return st.getAction();
                    }
                }
            }

            // Choose a new focus action and start again
            // Should clear exploringStates?
            if (!focusActivities.isEmpty()) {
                return findNextPath(source);
            }

            Logger.println("No more focus activities, can stop execution");
        }

        // Finished all activities
        return null;
    }

    private static String getNamespace(String activity) {
        return activity.substring(0, activity.lastIndexOf(".") - 1);
    }


    @Override
    protected Action selectNewActionNonnull() {

        String source = newState.getActivity();

        // For example in cases like LeakLauncherActivity
        if (!getNamespace(source).equals(namespace)) {
            Logger.format("Activity %s outside the namespace, trying to get out", source);
            return selectNewActionRandomly();
        }

        if (mode == Mode.INITIALIZING) {
            Logger.format("Mode INITIALIZING, going to find a path to a focus activity from %s", source);
            return findNextPath(source);
        }

        String target = reachingPath.get(reachingPath.size() - 1);

        if (target.equals(source)) {
            Logger.format("Reached target activity %s, moving to mode EXPLORING", target);
            mode = Mode.EXPLORING;
        }

        if (focusActivities.contains(source)) {
            Logger.format("Reached focus activity %s (but not target %s), moving to mode EXPLORING", source, target);
            mode = Mode.EXPLORING;
        }

        if (mode == Mode.REACHING) {

            String expectedNext = getExpectedNext(currentState.getActivity());

            // Mistakenly moved to an unexpected activity, then go back
            if (!expectedNext.equals(source) && !expectedNext.equals(currentState.getActivity())) {
                Logger.format("Mode REACHING, strayed from target %s and now in %s", expectedNext, source);

                return newState.getBackAction();
            }

            Logger.format("Mode REACHING, trying to reach target activity %s from source activity %s", target, source);
            return reachingModeAction(source, target);
        }

        if (mode == Mode.EXPLORING) {
            Logger.format("Mode EXPLORING, exhausting actions of activity %s", source);
            return exploringModeAction(source);
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