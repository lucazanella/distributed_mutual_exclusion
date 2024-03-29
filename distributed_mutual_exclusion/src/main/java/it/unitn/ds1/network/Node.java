package it.unitn.ds1.network;

import akka.actor.AbstractActor;
import akka.actor.ActorRef;
import akka.actor.Props;
import it.unitn.ds1.messages.*;
import scala.concurrent.duration.Duration;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import static it.unitn.ds1.DistributedMutualExclusion.CRITICAL_SECTION_TIME;
import static it.unitn.ds1.DistributedMutualExclusion.BOOTSTRAP_DELAY;
import static it.unitn.ds1.DistributedMutualExclusion.CRASH_TIME;
import static it.unitn.ds1.DistributedMutualExclusion.REQUEST_COMMAND;
import static it.unitn.ds1.DistributedMutualExclusion.CRASH_COMMAND;

/**
 * Represents a node of the computer network.
 */
public class Node extends AbstractActor {

    /**
     * The id of the node.
     */
    private int id;
    /**
     * List of neighbor nodes.
     */
    private List<ActorRef> neighbors = null;
    /**
     * Location of the privilege relative to the node itself.
     */
    private ActorRef holder = null;
    /**
     * Queue containing the neighbors that have sent a REQUEST message. to the
     * node itself.
     */
    private LinkedList<ActorRef> requestQ = new LinkedList<>();
    /**
     * Boolean that indicates if the node is executing the critical section.
     */
    private Boolean using = false;
    /**
     * Boolean that indicates if the node has sent a REQUEST message to the
     * holder.
     */
    private Boolean asked = false;
    /**
     * Boolean that indicates if the node is crashed.
     */
    private Boolean isCrashed = false;
    /**
     * Boolean that indicates if the node is in recovery phase.
     */
    private Boolean isRecovering = false;
    /**
     * Dictionary containing node and advise message pairs for which the node
     * has received an advise message from.
     */
    private HashMap<ActorRef, AdviseMessage> adviseMessages = new HashMap<>();
    /**
     * Object used to log messages for the application.
     */
    private final static Logger LOGGER = Logger.getLogger(Logger.GLOBAL_LOGGER_NAME);

    /**
     * Creates a Node with the information about the id of the node.
     *
     * @param id The id of the node.
     */
    public Node(int id) {
        super();
        this.id = id;
    }

    /**
     * Used by the system to create actors.
     *
     * @param id The id of the node.
     * @return The actor that we want to create.
     */
    static public Props props(int id) {
        return Props.create(Node.class, () -> new Node(id));
    }

    /**
     * Sends the Privilege Message to one of its neighbors who has requested the
     * privilege and sets the holder accordingly. The necessary requirement to
     * send the Privilege Message is that the node must hold the privilege but
     * is not using it, and the request queue is not empty. If the oldest
     * request for the privilege has come from another node, then the privilege
     * will be sent in its direction, otherwise the node will begin to use the
     * privilege.
     */
    private void assignPrivilege() {

        if (holder.equals(getSelf()) & !using & !requestQ.isEmpty()) {
            holder = requestQ.remove();
            asked = false;
            if (holder.equals(getSelf())) {
                using = true;

                LOGGER.setLevel(Level.INFO);
                LOGGER.info("Node " + this.id + " ENTER critical section...");

                getContext().system().scheduler().scheduleOnce(Duration.create(CRITICAL_SECTION_TIME, TimeUnit.MILLISECONDS),
                        getSelf(),
                        new ExitCriticalSection(),
                        getContext().system().dispatcher(), getSelf()
                );

            } else {
                PrivilegeMessage m = new PrivilegeMessage(this.id);
                holder.tell(m, getSelf());
            }
        }
    }

    /**
     * Sends a Request Message to the holder because the node does not have the
     * privilege, but wants it either for itself or others. If a Request Message
     * has already been sent to the holder, the Request Message would not be
     * sent again.
     */
    private void makeRequest() {
        LOGGER.setLevel(Level.INFO);

        // A node can request the privilege only if it has received the INITIALIZE message
        if (holder == null) {
            LOGGER.severe("Node " + id + " is trying to request the PRIVILEGE but has not received the INITIALIZE message");
        } else {
            if (holder != getSelf() & !requestQ.isEmpty() & !asked) {
                holder.tell(new RequestMessage(this.id), getSelf());
                asked = true;
            }
        }
    }

    /**
     * Sends an Initialize Message to each of its neighbors to initialize the
     * algorithm.
     */
    private void initialize() {
        // We only schedule an init message to the starter itself to account
        // for setup time
        getContext().system().scheduler().scheduleOnce(
                Duration.create(BOOTSTRAP_DELAY, TimeUnit.MILLISECONDS),
                getSelf(),
                new InitializeMessage(this.id),
                getContext().system().dispatcher(), getSelf()
        );
    }

    /**
     * Emulates a crash and a recovery in a given time.
     *
     * @param recoverIn Number of milliseconds between the crash and the
     * recovery.
     */
    private void crash(int recoverIn) {

        this.isCrashed = true;
        LOGGER.setLevel(Level.INFO);
        LOGGER.info("Node " + id + " CRASHED");
        // setting a timer to "recover"

        this.holder = null;
        this.using = null;
        this.asked = null;
        this.requestQ.clear();

        getContext().system().scheduler().scheduleOnce(Duration.create(recoverIn, TimeUnit.MILLISECONDS),
                getSelf(),
                new Recovery(), // message sent to myself
                getContext().system().dispatcher(), getSelf()
        );
    }

    /**
     * Define the mapping between incoming message classes and the methods of
     * the actor.
     *
     * @return The reaction on the incoming message class.
     */
    @java.lang.Override
    public Receive createReceive() {
        return receiveBuilder()
                .match(Bootstrap.class, this::onBootstrap)
                .match(InitializeMessage.class, this::onInitializeMessage)
                .match(RequestMessage.class, this::onRequestMessage)
                .match(PrivilegeMessage.class, this::onPrivilegeMessage)
                .match(RestartMessage.class, this::onRestartMessage)
                .match(AdviseMessage.class, this::onAdviseMessage)
                .match(Recovery.class, this::onRecovery)
                .match(UserInput.class, this::onUserInput)
                .match(ExitCriticalSection.class, this::onExitCriticalSection)
                .build();
    }

    /**
     * The reaction on an incoming Boostrap message.
     *
     * @param msg The incoming Boostrap message.
     */
    private void onBootstrap(Bootstrap msg) {
        LOGGER.setLevel(Level.INFO);
        LOGGER.info("BOOTSTRAP message received by node " + id + ". Node " + id + " has: " + msg.getNeighbors().size() + " neighbors");

        this.neighbors = msg.getNeighbors();

        if (msg.isStarter()) {
            LOGGER.info("STARTER of the protocol is node " + id);

            initialize();
        }
    }

    /**
     * The reaction on an incoming Initialize Message.
     *
     * @param msg The incoming Initialize Message.
     */
    private void onInitializeMessage(InitializeMessage msg) {
        LOGGER.setLevel(Level.INFO);
        LOGGER.info("INITIALIZE message received by node " + this.id + " from node " + msg.getSenderId());

        ActorRef sender = getSender();
        if (sender == null) {
            LOGGER.severe("The sender of the INITIALIZE message received by node" + this.id + " is NULL");
        }
        holder = getSender();
        for (ActorRef neighbor : neighbors) {
            if (neighbor != holder) {
                neighbor.tell(new InitializeMessage(this.id), getSelf());
            }
        }

    }

    /**
     * The reaction on an incoming Request Message.
     *
     * @param msg The incoming Request Message.
     */
    private void onRequestMessage(RequestMessage msg) {
        // procedures assignPrivilege and makeRequest are not called during crash and recovery phase
        if (!isCrashed) {
            LOGGER.setLevel(Level.INFO);
            LOGGER.info("REQUEST message received by node " + id + " from node " + msg.getSenderId());

            requestQ.add(getSender());

            if (!isRecovering) {
                assignPrivilege();
                makeRequest();
            }
        }
    }

    /**
     * The reaction on an incoming Privilege Message.
     *
     * @param msg The incoming Privilege Message.
     */
    private void onPrivilegeMessage(PrivilegeMessage msg) {
        if (!isCrashed) {
            LOGGER.setLevel(Level.INFO);
            LOGGER.info("PRIVILEGE message received by node " + id + " from node " + msg.getSenderId());
            this.holder = getSelf();

            if (!isRecovering) {
                assignPrivilege();
                makeRequest();
            }
        }
    }

    /**
     * The reaction on an incoming Restart Message.
     *
     * @param msg The incoming Restart Message.
     */
    private void onRestartMessage(RestartMessage msg) {
        LOGGER.setLevel(Level.INFO);
        LOGGER.info("RESTART message received by node " + id + " from node " + msg.getSenderId());

        // DONE: send and ADVISE message informing the recovering node of the state of the relationship with the current node
        boolean isXInRequestQ = this.requestQ.contains(getSender());
        boolean isXHolder = this.holder == getSender();

        getSender().tell(new AdviseMessage(id, isXHolder, isXInRequestQ, asked), getSelf());
    }

    /**
     * The reaction on an incoming Advise Message.
     *
     * @param msg The incoming Advise Message.
     */
    private void onAdviseMessage(AdviseMessage msg) {
        LOGGER.setLevel(Level.INFO);
        LOGGER.info("ADVISE message received by node " + id + " from node " + msg.getSenderId());

        adviseMessages.put(getSender(), msg);

        // the node is in recovery phase until all ADVISE messages from each neighbor are received
        if (adviseMessages.keySet().containsAll(neighbors)) {
            // All advise messages have been received
            // Recovery procedure

            String requestQIds = "[";
            Integer holderId = id;
            boolean holdsPrivilege = false;

            // 1.determining holder, ASKED and USING
            using = false;
            asked = false;

            // After the crash, if no PRIVILEGE message is received, holder is equal to null.
            if (holder != null) {
                // This means that this node holds the privilege. This happens because
                // during the recovery a PRIVILEGE message, that was sent after an ADVISE message,
                // was received before the ADVISE message itself. The outdated information contained
                // in the ADVISE message will not be considered.
                holdsPrivilege = true;
            } else {
                holder = getSelf();
            }

            for (ActorRef neighbor : adviseMessages.keySet()) {
                AdviseMessage currentMsg = adviseMessages.get(neighbor);

                if (!currentMsg.isXHolder()) {
                    if (holdsPrivilege) {
                        // In such a situation the ADVISE message contains outdated information.
                        // The node has received the privilege from the current neighbor, meaning
                        // that the node must have requested it, so asked must be true
                        asked = true;
                        requestQ.add(getSelf());
                        requestQIds += id + ", ";
                    } else {
                        // It means that this node is not privileged
                        this.holder = neighbor;
                        holderId = currentMsg.getSenderId();
                        if (currentMsg.isXInRequestQ()) {
                            this.asked = true;
                            requestQ.add(getSelf());
                            requestQIds += id + ", ";
                        }
                    }
                } else {
                    // Reconstruct Request Queue
                    if (currentMsg.isAskedY()) {
                        // During recovery there is the possibility that a node Y will be placed in X's requestQ
                        // twice. This check avoid for such a duplication
                        if (!requestQ.contains(neighbor)) {
                            requestQ.add(neighbor);
                        }
                        requestQIds += currentMsg.getSenderId() + ", ";
                    }
                }
            }
            if (!requestQ.isEmpty()) {
                requestQIds = requestQIds.substring(0, requestQIds.length() - 2);
            }
            requestQIds += "]";
            adviseMessages.clear();
            isRecovering = false;

            LOGGER.info("Node " + id + " has completed RECOVERY. "
                    + "Holder: " + holderId + ", "
                    + "Asked: " + asked + ", "
                    + "RequestQ: " + requestQIds + ", "
                    + "Using: " + using);

            // After the recovery phase is completed, the node recommence its participation in the algorithm
            assignPrivilege();
            makeRequest();
        }
    }

    /**
     * The reaction on an incoming User Input message.
     *
     * @param msg The incoming User Input message.
     */
    private void onUserInput(UserInput msg) {
        LOGGER.setLevel(Level.INFO);

        switch (msg.getCommandId()) {
            case REQUEST_COMMAND:
                if (!isCrashed) {
                    LOGGER.info("REQUEST command received by node " + id + " from user");
                    requestQ.add(self());
                    if (!isRecovering) {
                        assignPrivilege();
                        makeRequest();
                    }
                } else {
                    System.out.println("WARNING: Node " + id + " is crashed. It cannot accept REQUEST commands");
                }
                break;
            case CRASH_COMMAND:
                if (!isCrashed && !isRecovering && using != null && !using) {
                    LOGGER.info("CRASH command received by node " + id + " from user");
                    crash(CRASH_TIME);
                } else {
                    System.out.println("WARNING: Node " + id + " is either crashed, recovering or in the critical section. It cannot accept CRASH commands");
                }
                break;
        }
    }

    /**
     * The reaction on an incoming Exit Critical Section message.
     *
     * @param msg The incoming Exit Critical Section message.
     */
    private void onExitCriticalSection(ExitCriticalSection msg) {
        LOGGER.setLevel(Level.INFO);
        LOGGER.info("Node " + this.id + " EXIT critical section...");

        this.using = false;
        assignPrivilege();
        makeRequest();
    }

    /**
     * The reaction on an incoming Recovery message.
     *
     * @param msg The incoming Recovery message.
     */
    private void onRecovery(Recovery msg) {
        LOGGER.setLevel(Level.INFO);
        LOGGER.info("Node " + this.id + " starts RECOVERY");

        /* We assume that the crash lasts long enough to guarantee that all
         * messages sent before crashing are received by all nodes.
         */
        this.isCrashed = false;
        this.isRecovering = true;
        RestartMessage restartMessage = new RestartMessage(this.id);
        for (ActorRef neighbor : neighbors) {
            neighbor.tell(restartMessage, getSelf());
        }
    }
}
