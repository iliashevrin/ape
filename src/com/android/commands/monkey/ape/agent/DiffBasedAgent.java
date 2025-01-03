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



    public static class FocusTransition {
        String source;
        String target;
        @Override
        public String toString() {
            return source + " -> " + target;
        }
        @Override
        public int hashCode() {
            return Objects.hash(source, target);
        }
        @Override
        public boolean equals(Object obj) {
            if (this == obj)
                return true;
            if (obj == null)
                return false;
            if (getClass() != obj.getClass())
                return false;
            FocusTransition other = (FocusTransition) obj;
            return Objects.equals(source, other.source) && Objects.equals(target, other.target);
        }
    }


    public static class LogActivity {
        @Override
        public String toString() {
            return "[" + activity + ", " + id + "]";
        }
        @Override
        public int hashCode() {
            return Objects.hash(activity, id);
        }
        @Override
        public boolean equals(Object obj) {
            if (this == obj)
                return true;
            if (obj == null)
                return false;
            if (getClass() != obj.getClass())
                return false;
            ActivityFromLog other = (ActivityFromLog) obj;
            return Objects.equals(activity, other.activity) && Objects.equals(id, other.id);
        }
        String activity;
        String id;


    }


    public static class LogAction {
        ActionType actionType;
        String targetXpath;

        LogAction(ActionType actionType, String xpath) {
            this.actionType = actionType;
            this.targetXpath = xpath;
        }

        @Override
        public String toString() {
            return "[" + actionType + ", " + targetXpath + "]";
        }

        @Override
        public int hashCode() {
            return Objects.hash(actionType, targetXpath);
        }
        @Override
        public boolean equals(Object obj) {
            if (this == obj)
                return true;
            if (obj == null)
                return false;
            if (getClass() != obj.getClass())
                return false;
            Action other = (Action) obj;
            return Objects.equals(actionType, other.actionType) && Objects.equals(targetXpath, other.targetXpath);
        }
    }


    public static enum Mode {
        INITIALIZING, REACHING, EXPLORING;
    }




    private String logFile;
    private String namespace;

    private Set<FocusTransition> existingFocusTransitions = new HashSet<>();
    private Set<FocusTransition> newFocusTransitions = new HashSet<>();

    private Set<String> allActivities = new HashSet<>();
    private Set<String> erroneousActivities = new HashSet<>();
    private Set<State> exploringStates = new HashSet<>();

    private Mode mode = Mode.INITIALIZING;

    private List<String> reachingPath = new ArrayList<>();

    // Activity -> Activity -> Set of Actions
    private Map<LogActivity, Map<String, Set<LogAction>>> graph = new HashMap<>();




    public DiffBasedAgent(MonkeySourceApe ape, Graph graph, String previousLog, String manifestFile, String focusSet) {
        super(ape, graph);
        this.logFile = previousLog;

        Set<FocusTransition> transitions = parseFocusTransitions(focusSet);


        if (transitions.isEmpty()) {
            Logger.println("No focus activities, can stop execution");
        } else {

            buildATGFromLog(previousLog);

            for (FocusTransition ft : transitions) {
                if (isExistingTrans(ft)) {
                    existingFocusTransitions.add(ft);
                } else {
                    newFocusTransitions.add(ft);
                }
            }

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

    private static Set<FocusTransition> parseFocusTransitions(String focusSet) {
        Set<FocusTransition> transitions = new HashSet<>();
        for (String transitionString : focusSet.split(",")) {
            String[] split = transitionString.split("->");
            FocusTransition t = new FocusTransition();
            t.source = split[0];
            t.target = split[1];
            transitions.add(t);
        }
        return transitions;
    }

    private static boolean isExistingTrans(FocusTransition ft) {
        for (LogActivity source : graph.keySet()) {
            if (source.activity.equals(ft.source)) {
                for (LogActivity target : graph.get(source).keySet()) {
                    if (target.activity.equals(ft.target)) {
                        return true;
                    }
                }
            }
        }
        return false;
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
                    LogActivity source = parseActivity(line);

                    if (source.activity.endsWith("Activity") && !graph.containsKey(source)) {
                        graph.put(source, new HashMap<String, Set<LogAction>>());
                    }

                    while (!line.contains("Action: ")) {
                        line = readLine(reader);
                    }
                    ActionType actionType = parseActionType(line);
                    String xpath = parseTargetXpath(line);

                    while (!line.contains("Target: ")) {
                        line = readLine(reader);
                    }
                    LogActivity target = parseActivity(line);


                    // Also add target as node in graph
                    if (target.activity.endsWith("Activity") && !graph.containsKey(target)) {
                        graph.put(target, new HashMap<LogActivity, Set<LogAction>>());
                    }

                    if (!graph.get(source).containsKey(target)) {
                        graph.get(source).put(target, new HashSet<LogAction>());
                    }

                    graph.get(source).get(target).add(new LogAction(actionType, xpath));
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

    private static LogActivity parseActivity(String line) {
        String temp = line.split("@")[0].substring(5);
        LogActivity afl = new LogActivity();
        afl.activity = temp.substring(temp.lastIndexOf("]") + 1);
        afl.id = temp.substring(temp.indexOf(": ") + 2, temp.indexOf("["));
        return afl;
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
            return namespace.substring(0, namespace.lastIndexOf('.')-1);
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
    private List<String> nextPath(LogActivity source) {

        Queue<List<LogActivity>> q = new LinkedList<>();
        Set<LogActivity> visited = new HashSet<>();
//        q.add(Collections.singletonList(getMain()));
        q.add(Collections.singletonList(source));

        while (!q.isEmpty()) {

            List<LogActivity> curr = q.poll();
            LogActivity lastActivity = curr.get(curr.size()-1);
            visited.add(lastActivity);

            // Find a path towards a focus activity that is not erroneous
            if (focusActivities.contains(lastActivity) && !erroneousActivities.contains(lastActivity)) {
                Logger.format("New activity to reach is %s", lastActivity);
                return curr;
            }

            for (String next : graph.get(lastActivity).keySet()) {
                if (!visited.contains(next)) {
                    List<LogActivity> newPath = new ArrayList<>(curr);
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
            Logger.format("Reaching path does not contain source %s", source);
            return null;
        }

        String next = getExpectedNext(source);

        Set<LogAction> possibleActions = graph.get(source).get(next);

        // Should never happen
        if (possibleActions == null) {
            Logger.format("No action to take from %s to get to %s", source, next);
            return null;
        }

        Logger.format("Source=%s; Next=%s; #actions=%d",
                source, next, possibleActions.size());

        for (LogAction logAction : possibleActions) {
            try {
                Logger.format("Source=%s; Next=%s; Action=%s; Xpath=%s",
                        source, next, logAction.actionType, logAction.targetXpath);
                Name name = resolveName(logAction.targetXpath);

                if (name != null) {
                    ModelAction action = newState.getAction(name, logAction.actionType);
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

        Logger.format("None of the possible actions from %s to %s are valid", source, next);
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
                    newEdges.put(previous, new HashMap<String, Set<LogAction>>());
                }
                if (!newEdges.get(previous).containsKey(source)) {
                    newEdges.get(previous).put(source, new HashSet<LogAction>());
                }
                String xpath = parseTargetXpath(currentAction.toString());
                newEdges.get(previous).get(source).add(new LogAction(currentAction.getType(), xpath));
            }
        }

        // If not in focus, go back
        if (!focusActivities.contains(source)) {

            if (currentState != null) {
                Logger.format("Trying to go back from activity %s to activity %s", source, currentState.getActivity());
                for (StateTransition st : getGraph().getInStateTransitions(newState)) {
                    if (st.getTarget().equals(currentState.getActivity())) {
                        return st.getAction();
                    }
                }

                return newState.getBackAction();
            }

            // If current state is null it is probably the LeakLauncherActivity
            mode = Mode.INITIALIZING;
            return getStartAction(nextRestartAction());

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
        if (!getNamespace(source).startsWith(namespace)) {
            Logger.format("Activity %s outside the namespace, trying to get out", source);
            return selectNewActionRandomly();
        }

        if (mode == Mode.INITIALIZING) {
            Logger.format("Mode INITIALIZING, going to find a path to a focus activity from %s", source);
            return findNextPath(source);
        }

        String target = reachingPath.get(reachingPath.size() - 1);

        if (mode != Mode.EXPLORING) {
            if (target.equals(source)) {
                Logger.format("Reached target activity %s, moving to mode EXPLORING", target);
                mode = Mode.EXPLORING;
            } else if (focusActivities.contains(source)) {
                Logger.format("Reached focus activity %s (but not target %s), moving to mode EXPLORING", source, target);
                mode = Mode.EXPLORING;
            }
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
            Logger.format("Mode EXPLORING, now in activity %s", source);
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