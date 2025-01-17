package it.polimi.ingsw.server;

import it.polimi.ingsw.client.messages.actions.ChallengerPhaseAction;
import it.polimi.ingsw.client.messages.actions.UserAction;
import it.polimi.ingsw.client.messages.actions.WorkerSetupAction;
import it.polimi.ingsw.client.messages.actions.turnactions.StartTurnAction;
import it.polimi.ingsw.constants.Constants;
import it.polimi.ingsw.controller.Controller;
import it.polimi.ingsw.model.Game;
import it.polimi.ingsw.model.player.Player;
import it.polimi.ingsw.model.player.PlayerColors;
import it.polimi.ingsw.server.answers.*;
import it.polimi.ingsw.server.answers.turn.StartTurnMessage;

import java.beans.PropertyChangeSupport;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * GameHandler class handles a single match, instantiating a game mode (Game class) and a main controller (Controller
 * class). It also manages the startup phase, like the marker's color selection.
 * @author Luca Pirovano

 */
public class GameHandler {
    private static final String PLAYER = "Player";
    private final Server server;
    private final Controller controller;
    private final Game game;
    private final PropertyChangeSupport controllerListener = new PropertyChangeSupport(this);
    private final Logger logger = Logger.getLogger(getClass().getName());
    private final Random rnd = new Random();
    private int started;
    private int playersNumber;

    /**
     * Constructor GameHandler creates a new GameHandler instance.
     *
     * @param server of type Server - the main server class.
     */
    public GameHandler(Server server) {
        this.server = server;
        started = 0;
        game = new Game();
        controller = new Controller(game, this);
        controllerListener.addPropertyChangeListener(controller);
    }


    /**
     * Method isStarted returns if the game has started (the started attribute becomes true after the challenger
     * selection phase).
     * @return int - the current game phase.
     */
    public int isStarted() {
        return started;
    }


    /**
     * Method setPlayersNumber sets the active players number of the match, decided by the first connected player.
     *
     * @param playersNumber of type int - the number of the playing clients.
     *
     */
    public void setPlayersNumber(int playersNumber) {
        this.playersNumber = playersNumber;
    }


    /**
     * Method setupPlayer receives a nickname from the server application and creates a new player in the game class.
     *
     * @param nickname of type String - the chosen nickname, after a duplicates check.
     * @param clientID of type int the - client unique id generated from server.
     */
    public void setupPlayer(String nickname, int clientID) {
        game.createNewPlayer(new Player(nickname, clientID));
    }

    /**
     * Method getCurrentPlayerID returns the current player client ID, getting it from the currentPlayer
     * reference in the Game class.
     *
     * @return the currentPlayerID (type int) of this GameHandler object.
     */
    public int getCurrentPlayerID() {
        return game.getCurrentPlayer().getClientID();
    }


    /**
     * Method singleSend sends a message to a client, identified by his ID number, through the server socket.
     *
     * @param message of type Answer - the message to be sent to the client.
     * @param id of type int - the unique identification number of the client to be contacted.
     */
    public void singleSend(Answer message, int id) {
        server.getClientByID(id).send(message);
    }


    /**
     * Method sendAll does the same as the previous method, but it iterates on all the clients present in the game.
     * It's a full effects broadcast.
     *
     * @param message of type Answer - the message to broadcast (at single match participants' level).
     */
    public void sendAll(Answer message) {
        for(Player countPlayer:game.getActivePlayers()) {
            singleSend(message, server.getIDByNickname(countPlayer.getNickname()));
        }
    }


    /**
     * Method sendAllExcept makes the same as the previous method, but it iterates on all the clients present in the
     * game, except the declared one.
     *
     * @param message of type Answer - the message to be transmitted.
     * @param excludedID of type int - the client which will not receive the communication.
     */
    public void sendAllExcept(Answer message, int excludedID) {
        for(Player countPlayer:game.getActivePlayers()) {
            if(server.getIDByNickname(countPlayer.getNickname())!=excludedID) {
                singleSend(message, countPlayer.getClientID());
            }
        }
    }


    /**
     * Method setup handles the preliminary player setup phase; in this phase the color of workers' markers will be
     * asked the player, with a double check (on both client and server sides) of the validity of them
     * (also in case of duplicate colors).
     */
    public void setup() {
        if(started==0) started=1;
        ColorMessage req = new ColorMessage("Please choose your workers' color.");
        req.addRemaining(PlayerColors.notChosen());
        if(playersNumber==2 && PlayerColors.notChosen().size()>1) {
            String nickname = game.getActivePlayers().get(playersNumber - PlayerColors.notChosen().size() + 1).
                    getNickname();
            singleSend(req, server.getIDByNickname(nickname));
            sendAllExcept(new CustomMessage("User " + nickname + " is choosing his color!", false),
                    server.getIDByNickname(nickname));
            return;
        }
        else if(playersNumber==3 && !PlayerColors.notChosen().isEmpty()) {
            String nickname = game.getActivePlayers().get(playersNumber - PlayerColors.notChosen().size()).
                    getNickname();
            if(PlayerColors.notChosen().size()==1) {
                    game.getPlayerByNickname(nickname).setColor(PlayerColors.notChosen().get(0));
                singleSend(new CustomMessage("\nThe society decides for you! You have the " +
                        PlayerColors.notChosen().get(0) + " color!\n", false), server.getIDByNickname(nickname));
                singleSend(new ColorMessage(null, PlayerColors.notChosen().get(0).toString()),
                        server.getIDByNickname(nickname));
                PlayerColors.choose(PlayerColors.notChosen().get(0));
                try {
                    TimeUnit.SECONDS.sleep(1);
                } catch (InterruptedException e) {
                    logger.log(Level.SEVERE, e.getMessage(), e);
                    Thread.currentThread().interrupt();
                }
            }
            else {
                server.getClientByID(server.getIDByNickname(nickname)).send(req);
                sendAllExcept(new CustomMessage("User " + nickname + " is choosing his color!", false),
                        server.getIDByNickname(nickname));
                return;
            }
        }

        //Challenger section
        game.setCurrentPlayer(game.getActivePlayers().get(rnd.nextInt(playersNumber)));
        singleSend(new ChallengerMessages(game.getCurrentPlayer().getNickname() + ", you are the challenger!\nYou " +
                        "have to choose gods power. Type GODLIST to get a list of available gods, GODDESC <god name>" +
                        " to get a god's description and ADDGOD <god name> to add a God power to deck.\n" +
                        (playersNumber - game.getDeck().getCards().size()) + " gods left."),
                game.getCurrentPlayer().getClientID());
        sendAllExcept(new CustomMessage(game.getCurrentPlayer().getNickname() + " is the challenger! " +
                "Please wait while he/she chooses the god powers.", false), game.getCurrentPlayer().getClientID());
        controller.setSelectionController(game.getCurrentPlayer().getClientID());
    }


    /**
     * Method getController returns the main game manager controller.
     *
     * @return the controller (type Controller) of this GameHandler object.
     * @see it.polimi.ingsw.controller.Controller for more information.
     */
    public Controller getController() {
        return controller;
    }

    /**
     * @return the server class.
     */
    public Server getServer() {
        return server;
    }


    /**
     * Method makeAction handles an action received from a single client.
     * It makes several instance checks. It's based on the value of "started", which represents the current
     * game phase, in this order:
     * - 0: color phase
     * - 1: challenger phase;
     * - 2: players select their god powers;
     * - 3: board worker placement;
     * - 4: the game has started.
     *
     * @param action of type UserAction - the action sent by the client.
     * @param type of type String - the action type.
     */
    public void makeAction(UserAction action, String type) {
        switch (type) {
            case "ChallengerPhase" -> challengerPhase(action);
            case "WorkerPlacement" -> workerPlacement((WorkerSetupAction) action);
            case "turnController" -> controllerListener.firePropertyChange(type, null, action);
            default -> singleSend(new GameError(ErrorsType.INVALIDINPUT), getCurrentPlayerID());
        }
    }


    /**
     * Method workerPlacement handles the worker placement phase by checking the correctness of the user's input
     * and if the selected cell is free or occupied by someone else.
     *
     * @param action of type WorkerSetupMessage - the placement action.
     */
    public void workerPlacement(WorkerSetupAction action) {
        if(action!=null) {
            controllerListener.firePropertyChange("workerPlacement", null, action);
            if(game.getCurrentPlayer().getWorkers().get(0).getPosition()==null) {
                return;
            }
            game.nextPlayer();
        }
        if(game.getCurrentPlayer().getWorkers().get(0).getPosition()!=null) {
            MatchStartedMessage startedMessage = new MatchStartedMessage();
            game.getActivePlayers().forEach(n -> startedMessage.setPlayerMapColor(n.getNickname(),
                    n.getColor().toString()));
            game.getActivePlayers().forEach(n -> startedMessage.setPlayerMapGod(n.getNickname(),
                    n.getCard().toString()));
            sendAll(startedMessage);
            try {
                TimeUnit.SECONDS.sleep(1);
            } catch (InterruptedException e) {
                System.err.println(e.getMessage());
                Thread.currentThread().interrupt();
            }
            controllerListener.firePropertyChange("turnController", null, new StartTurnAction());
            sendAllExcept(new StartTurnMessage(controller.getModel().getCurrentPlayer().getNickname()),
                    getCurrentPlayerID());
            started = 4;
            return;
        }
        List<int[]> spaces = new ArrayList<>();
        for(int i = Constants.GRID_MIN_SIZE; i<Constants.GRID_MAX_SIZE; i++) {
            for(int j = Constants.GRID_MIN_SIZE; j<Constants.GRID_MAX_SIZE; j++) {
                if(game.getGameBoard().getSpace(i, j).isEmpty()) {
                    int[] coords = new int[2];
                    coords[0] = game.getGameBoard().getSpace(i, j).getRow();
                    coords[1] = game.getGameBoard().getSpace(i, j).getColumn();
                    spaces.add(coords);
                }
            }
        }
        singleSend(new WorkerPlacement(game.getCurrentPlayer().getNickname() + ", choose your workers" +
                " position by typing SET <row1 <col1> <row2> <col2> where 1 and 2 indicates worker number.", spaces),
                getCurrentPlayerID());
        sendAllExcept(new CustomMessage(PLAYER + " " + game.getCurrentPlayer().getNickname() + " is choosing " +
                "workers' position.", false), getCurrentPlayerID());
    }


    /**
     * Method challengerPhaseChoose handles the second game phase: the user chooses his god card.
     * If he is in the wrong turn phase (checked by the "started" int value) an error message in created and sent to the
     * client, who is requested to send another command.
     *
     * @param userAction of type ChallengerPhaseAction - the action of the current player.
     * @param godSelection of type String - the selection of the god card.
     */
    public void challengerPhaseChoose(ChallengerPhaseAction userAction, String godSelection) {
        if(userAction.action.equals("CHOOSE")) {
            controllerListener.firePropertyChange(godSelection, null, userAction);
            if (game.getDeck().getCards().size() > 1) {
                if(!game.getCurrentPlayer().getWorkers().isEmpty() && game.getDeck().getCards().size()>1) {
                    game.nextPlayer();
                    singleSend(new ChallengerMessages(server.getNicknameByID(getCurrentPlayerID()) +", please "
                            + "choose your god power from one of the list below.\n\n", game.getDeck().getCards()),
                            getCurrentPlayerID());
                    sendAllExcept(new CustomMessage(PLAYER + " " + game.getCurrentPlayer().getNickname() +
                            " is choosing his god power...", false), getCurrentPlayerID());
                    return;
                }
                else if(!game.getCurrentPlayer().getWorkers().isEmpty() && game.getDeck().getCards().size()==1) {
                    game.nextPlayer();
                    return;
                }
                singleSend(new ChallengerMessages(server.getNicknameByID(getCurrentPlayerID()) +", please "
                                + "choose your god power from one of the list below.\n\n", game.getDeck().getCards()),
                        getCurrentPlayerID());
                sendAllExcept(new CustomMessage(PLAYER + " " + game.getCurrentPlayer().getNickname() +
                        " is choosing his god power...", false), getCurrentPlayerID());
            } else if (game.getDeck().getCards().size() == 1) {
                game.nextPlayer();
                controllerListener.firePropertyChange(godSelection, null,
                        new ChallengerPhaseAction("LASTSELECTION"));
                ArrayList<String> players = new ArrayList<>();
                game.getActivePlayers().forEach(n -> players.add(n.getNickname()));
                singleSend(new ChallengerMessages(game.getCurrentPlayer().getNickname() + ", choose the " +
                        "starting player by typing STARTER <number-of-player>", true, players),
                        game.getCurrentPlayer().getClientID());
                sendAllExcept(new CustomMessage(PLAYER + " " + game.getCurrentPlayer().getNickname() + " is " +
                        "choosing the starting player!", false),
                        game.getCurrentPlayer().getClientID());
                started = 3;
            }
        }
        else {
            singleSend(new ChallengerMessages(Constants.ANSI_RED + "Error: not in correct game phase for this command!"
                    + Constants.ANSI_RESET), getCurrentPlayerID());
        }
    }


    /**
     * Method challengerPhase handles the challenger game phase (based on the listener message).
     * It triggers the correct method relying on the started value.
     *
     * @param action of type UserAction - the action to be performed
     */
    public void challengerPhase(UserAction action) {
        ChallengerPhaseAction userAction = (ChallengerPhaseAction)action;
        String godSelection = "godSelection";
        if (started < 2 || started>3) {
            if(userAction.startingPlayer!=null) {
                singleSend(new GameError(ErrorsType.INVALIDINPUT), game.getCurrentPlayer().getClientID());
                return;
            }
            else if(userAction.action.equals("CHOOSE")) {
                singleSend(new ChallengerMessages(Constants.ANSI_RED + "Error: not in correct game phase for " +
                        "this command!" + Constants.ANSI_RESET), getCurrentPlayerID());
                return;
            }
            controllerListener.firePropertyChange(godSelection, null, action);
            if (game.getDeck().getCards().size() == playersNumber) {
                started = 2;
                game.nextPlayer();
                singleSend(new ChallengerMessages(server.getNicknameByID(getCurrentPlayerID()) +
                        ", please choose your god power from one of the list below.", game.getDeck().getCards()),
                        getCurrentPlayerID());
                sendAllExcept(new CustomMessage(PLAYER + " " + game.getCurrentPlayer().getNickname() +
                        " is" + " choosing his god power...", false), getCurrentPlayerID());
            }
        }
        else if (started == 2) {
            if(userAction.startingPlayer!=null) {
                singleSend(new GameError(ErrorsType.INVALIDINPUT), game.getCurrentPlayer().getClientID());
                return;
            }
            challengerPhaseChoose(userAction, godSelection);
        }
        else if(userAction.startingPlayer!=null) {
            if(userAction.startingPlayer < 0 || userAction.startingPlayer > game.getActivePlayers().size()) {
                singleSend(new GameError(ErrorsType.INVALIDINPUT, "Error: value out of range!"),
                        getCurrentPlayerID());
                return;
            }
            game.setCurrentPlayer(game.getActivePlayers().get(userAction.startingPlayer));
            singleSend(new CustomMessage(game.getCurrentPlayer().getNickname() + ", you are the first " +
                    "player; let's go!", false), getCurrentPlayerID());
            sendAllExcept(new CustomMessage("Well done! " + game.getCurrentPlayer().getNickname() + " is " +
                    "the first player!", false), getCurrentPlayerID());
            started = 5;
            workerPlacement(null);
        }
    }


    /**
     * Method unregisterPlayer unregisters a player identified by his unique ID after a disconnection event or message.
     *
     * @param id of type int - the unique id of the client to be unregistered.
     */
    public void unregisterPlayer(int id) {
        game.removePlayer(game.getPlayerByID(id));
    }


    /**
     * Method endGame terminates the game, disconnecting all the players.
     * This method is invoked after a disconnection of a player.
     * It also unregisters each client connected to the server, freeing a new lobby.
     *
     * @param leftNickname of type String the nickname of the player who left the game.
     */
    public void endGame(String leftNickname) {
        sendAll(new ConnectionMessage(PLAYER + " " + leftNickname + " left the game, the match will now end." +
                "\nThanks for playing!", 1));
        while(!game.getActivePlayers().isEmpty()) {
            server.getClientByID(game.getActivePlayers().get(0).getClientID()).getConnection().close();
        }
    }


    /**
     * Method endGame terminates the game, disconnecting all the players. This method is invoked after a player has won.
     * It also unregisters each client connected to the server, freeing a new lobby.
     */
    public void endGame() {
        while(!game.getActivePlayers().isEmpty()) {
            server.getClientByID(game.getActivePlayers().get(0).getClientID()).getConnection().close();
        }
    }
}