// Copyright 2017 Google Inc.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.


package codeu.chat.server;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Arrays;
import java.util.Collection;

import codeu.chat.common.Conversation;
import codeu.chat.common.ConversationSummary;
import codeu.chat.common.LinearUuidGenerator;
import codeu.chat.common.Message;
import codeu.chat.common.NetworkCode;
import codeu.chat.common.Relay;
import codeu.chat.common.Time;
import codeu.chat.common.User;
import codeu.chat.common.Uuid;
import codeu.chat.common.Uuids;
import codeu.chat.util.Logger;
import codeu.chat.util.Serializers;
import codeu.chat.util.Timeline;
import codeu.chat.util.connections.Connection;

import java.sql.*;

public final class Server {

  private static final Logger.Log LOG = Logger.newLog(Server.class);

  private static final int RELAY_REFRESH_MS = 5000;  // 5 seconds

  private final Timeline timeline = new Timeline();

  private final Uuid id;
  private final byte[] secret;

  private final Model model = new Model();
  private final View view = new View(model);
  private final Controller controller;

  private final Relay relay;
  private Uuid lastSeen = Uuids.NULL;

  public Server(final Uuid id, final byte[] secret, final Relay relay) {

    this.id = id;
    this.secret = Arrays.copyOf(secret, secret.length);

    this.controller = new Controller(id, model);
    this.relay = relay;



/* Beginning of experimental code */
    /* Set up database holding persistent data. */
    try {
        /* Connect to the database (or create a new one if it does not exist). */
        java.sql.Connection conn = null;
        java.sql.Statement statement = null;
        java.sql.ResultSet result = null;
        java.sql.ResultSet result1 = null;
        java.sql.Statement statement1 = null;

        Class.forName("org.sqlite.JDBC");
        conn = DriverManager.getConnection("jdbc:sqlite::test.db");

        /* Set up the tables to hold Users, Conversations, and Messages,
         * if the tables do not already exist. */
        statement = conn.createStatement();
        statement1 = conn.createStatement();

        String query = "SELECT name FROM sqlite_master WHERE type='table' AND name='USERS'";
        result = statement.executeQuery(query);


        /* If the users table already exists, check if there are users to be added
         * to the data structures. */
        if (result.next()) {
            LOG.info("Adding user to data structures\n");
            query = "SELECT * from USERS";
            result = statement.executeQuery(query);

            /* If users are already present in persistent storage, add them to the
             * data structures. */
            while (result.next()) {
                this.controller.newUser(Uuids.parse(result.getString("ID")),
                                        result.getString("NAME"),
                                        result.getString("PASSWORD"),
                                        Time.fromMs(result.getLong("CREATION")), false);

            }

        } else {
            /* Otherwise, create a new table to hold users. */
            query = "CREATE TABLE USERS " + 
                    "(ID TEXT PRIMARY KEY        NOT NULL," +
                    "NAME      TEXT             NOT NULL," +
                    "CREATION  LONG             NOT NULL," +
                    "PASSWORD  TEXT             NOT NULL)";
            statement.executeUpdate(query);

        }

        query = "SELECT name FROM sqlite_master WHERE type='table' AND name='CONVERSATIONS'";
        result = statement.executeQuery(query);

        /* See if conversations already exist. If they do, cycle through the existing conversations
         * and add them to the program structures. */
        if (result.next()) {
            query = "SELECT * from CONVERSATIONS";
            result = statement.executeQuery(query);
            

            while (result.next()) {
               this.controller.newConversation(Uuids.parse(result.getString("ID")), 
                                            result.getString("TITLE"),
                                            Uuids.parse(result.getString("OWNER")), 
                                            Time.fromMs(result.getLong("CREATION")),
                                            false);


              /* Look for any messages tables tied to conversations (each conversation
               * has its own table for its messages), and add
               * existing messages to the program structures. */
              query = "SELECT name FROM sqlite_master WHERE type='table' AND name='MESSAGES_" + result.getString("ID") + "'";
              result1 = statement1.executeQuery(query);

              if (result1.next()) {
                query = "SELECT * from [MESSAGES_" + result.getString("ID") + "]";
                result1 = statement1.executeQuery(query);

                /* Add messages to the conversation */
                while (result1.next()) {
                  this.controller.newMessage(Uuids.parse(result1.getString("ID")),
                                             Uuids.parse(result1.getString("AUTHOR")),
                                             Uuids.parse(result.getString("ID")),
                                             result1.getString("CONTENT"),
                                             Time.fromMs(result1.getLong("CREATION")),
                                             false);
                }
              }
            }
        } else {
            /* Conversations table does not already exist, so make one. */
            query = "CREATE TABLE CONVERSATIONS " + 
                    "(ID TEXT PRIMARY KEY        NOT NULL," +
                    "OWNER     INT              NOT NULL," +
                    "CREATION  TEXT             NOT NULL," +
                    "TITLE     TEXT             NOT NULL)";
            statement.executeUpdate(query);
        }

        statement.close();
        statement1.close();
        conn.close();
    } catch(Exception e) {
        System.err.println(e.getClass().getName() + ": " + e.getMessage());
        System.exit(0);
    }

    timeline.scheduleNow(new Runnable() {
      @Override
      public void run() {
        try {

          LOG.info("Reading update from relay...");

          for (final Relay.Bundle bundle : relay.read(id, secret, lastSeen, 32)) {
            onBundle(bundle);
            lastSeen = bundle.id();
          }

        } catch (Exception ex) {

          LOG.error(ex, "Failed to read update from relay.");

        }

        timeline.scheduleIn(RELAY_REFRESH_MS, this);
      }
    });
  }

  public void handleConnection(final Connection connection) {
    timeline.scheduleNow(new Runnable() {
      @Override
      public void run() {
        try {

          LOG.info("Handling connection...");

          final boolean success = onMessage(
              connection.in(),
              connection.out());

          LOG.info("Connection handled: %s", success ? "ACCEPTED" : "REJECTED");
        } catch (Exception ex) {

          LOG.error(ex, "Exception while handling connection.");

        }

        try {
          connection.close();
        } catch (Exception ex) {
          LOG.error(ex, "Exception while closing connection.");
        }
      }
    });
  }

  private boolean onMessage(InputStream in, OutputStream out) throws IOException {

    final int type = Serializers.INTEGER.read(in);

    if (type == NetworkCode.NEW_MESSAGE_REQUEST) {

      final Uuid author = Uuids.SERIALIZER.read(in);
      final Uuid conversation = Uuids.SERIALIZER.read(in);
      final String content = Serializers.STRING.read(in);

      final Message message = controller.newMessage(author, conversation, content, true);

      Serializers.INTEGER.write(out, NetworkCode.NEW_MESSAGE_RESPONSE);
      Serializers.nullable(Message.SERIALIZER).write(out, message);

      timeline.scheduleNow(createSendToRelayEvent(
          author,
          conversation,
          message.id));

    } else if (type == NetworkCode.NEW_USER_REQUEST) {

      final String name = Serializers.STRING.read(in);
      final String password = Serializers.STRING.read(in);

      final User user = controller.newUser(name, password, false);

      Serializers.INTEGER.write(out, NetworkCode.NEW_USER_RESPONSE);
      Serializers.nullable(User.SERIALIZER).write(out, user);

    } else if (type == NetworkCode.NEW_CONVERSATION_REQUEST) {

      final String title = Serializers.STRING.read(in);
      final Uuid owner = Uuids.SERIALIZER.read(in);

      final Conversation conversation = controller.newConversation(title, owner, true);

      Serializers.INTEGER.write(out, NetworkCode.NEW_CONVERSATION_RESPONSE);
      Serializers.nullable(Conversation.SERIALIZER).write(out, conversation);

    } else if (type == NetworkCode.GET_USERS_BY_ID_REQUEST) {

      final Collection<Uuid> ids = Serializers.collection(Uuids.SERIALIZER).read(in);

      final Collection<User> users = view.getUsers(ids);

      Serializers.INTEGER.write(out, NetworkCode.GET_USERS_BY_ID_RESPONSE);
      Serializers.collection(User.SERIALIZER).write(out, users);

    } else if (type == NetworkCode.GET_ALL_CONVERSATIONS_REQUEST) {

      final Collection<ConversationSummary> conversations = view.getAllConversations();

      Serializers.INTEGER.write(out, NetworkCode.GET_ALL_CONVERSATIONS_RESPONSE);
      Serializers.collection(ConversationSummary.SERIALIZER).write(out, conversations);

    } else if (type == NetworkCode.GET_CONVERSATIONS_BY_ID_REQUEST) {

      final Collection<Uuid> ids = Serializers.collection(Uuids.SERIALIZER).read(in);

      final Collection<Conversation> conversations = view.getConversations(ids);

      Serializers.INTEGER.write(out, NetworkCode.GET_CONVERSATIONS_BY_ID_RESPONSE);
      Serializers.collection(Conversation.SERIALIZER).write(out, conversations);

    } else if (type == NetworkCode.GET_MESSAGES_BY_ID_REQUEST) {

      final Collection<Uuid> ids = Serializers.collection(Uuids.SERIALIZER).read(in);

      final Collection<Message> messages = view.getMessages(ids);

      Serializers.INTEGER.write(out, NetworkCode.GET_MESSAGES_BY_ID_RESPONSE);
      Serializers.collection(Message.SERIALIZER).write(out, messages);

    } else if (type == NetworkCode.GET_USER_GENERATION_REQUEST) {

      Serializers.INTEGER.write(out, NetworkCode.GET_USER_GENERATION_RESPONSE);
      Uuids.SERIALIZER.write(out, view.getUserGeneration());

    } else if (type == NetworkCode.GET_USERS_EXCLUDING_REQUEST) {

      final Collection<Uuid> ids = Serializers.collection(Uuids.SERIALIZER).read(in);

      final Collection<User> users = view.getUsersExcluding(ids);

      Serializers.INTEGER.write(out, NetworkCode.GET_USERS_EXCLUDING_RESPONSE);
      Serializers.collection(User.SERIALIZER).write(out, users);

    } else if (type == NetworkCode.GET_CONVERSATIONS_BY_TIME_REQUEST) {

      final Time startTime = Time.SERIALIZER.read(in);
      final Time endTime = Time.SERIALIZER.read(in);

      final Collection<Conversation> conversations = view.getConversations(startTime, endTime);

      Serializers.INTEGER.write(out, NetworkCode.GET_CONVERSATIONS_BY_TIME_RESPONSE);
      Serializers.collection(Conversation.SERIALIZER).write(out, conversations);

    } else if (type == NetworkCode.GET_CONVERSATIONS_BY_TITLE_REQUEST) {

      final String filter = Serializers.STRING.read(in);

      final Collection<Conversation> conversations = view.getConversations(filter);

      Serializers.INTEGER.write(out, NetworkCode.GET_CONVERSATIONS_BY_TITLE_RESPONSE);
      Serializers.collection(Conversation.SERIALIZER).write(out, conversations);

    } else if (type == NetworkCode.GET_MESSAGES_BY_TIME_REQUEST) {

      final Uuid conversation = Uuids.SERIALIZER.read(in);
      final Time startTime = Time.SERIALIZER.read(in);
      final Time endTime = Time.SERIALIZER.read(in);

      final Collection<Message> messages = view.getMessages(conversation, startTime, endTime);

      Serializers.INTEGER.write(out, NetworkCode.GET_MESSAGES_BY_TIME_RESPONSE);
      Serializers.collection(Message.SERIALIZER).write(out, messages);

    } else if (type == NetworkCode.GET_MESSAGES_BY_RANGE_REQUEST) {

      final Uuid rootMessage = Uuids.SERIALIZER.read(in);
      final int range = Serializers.INTEGER.read(in);

      final Collection<Message> messages = view.getMessages(rootMessage, range);

      Serializers.INTEGER.write(out, NetworkCode.GET_MESSAGES_BY_RANGE_RESPONSE);
      Serializers.collection(Message.SERIALIZER).write(out, messages);

    } else if (type == NetworkCode.SIGN_IN_REQUEST) {

      final String name = Serializers.STRING.read(in);
      final String password = Serializers.STRING.read(in);

      //final User user = controller.newUser(name, password);
      User response = view.getSignInStatus(name, password);
      
      Serializers.INTEGER.write(out, NetworkCode.SIGN_IN_RESPONSE);
      Serializers.nullable(User.SERIALIZER).write(out, response);


    } else {

      // In the case that the message was not handled make a dummy message with
      // the type "NO_MESSAGE" so that the client still gets something.

      Serializers.INTEGER.write(out, NetworkCode.NO_MESSAGE);

    }

    return true;
  }

  private void onBundle(Relay.Bundle bundle) {

    final Relay.Bundle.Component relayUser = bundle.user();
    final Relay.Bundle.Component relayConversation = bundle.conversation();
    final Relay.Bundle.Component relayMessage = bundle.user();

    User user = model.userById().first(relayUser.id());

    Conversation conversation = model.conversationById().first(relayConversation.id());

    if (conversation == null) {

      // As the relay does not tell us who made the conversation - the first person who
      // has a message in the conversation will get ownership over this server's copy
      // of the conversation.
      conversation = controller.newConversation(relayConversation.id(),
                                                relayConversation.text(),
                                                user.id,
                                                relayConversation.time(), true);
    }

    Message message = model.messageById().first(relayMessage.id());

    if (message == null) {
      message = controller.newMessage(relayMessage.id(),
                                      user.id,
                                      conversation.id,
                                      relayMessage.text(),
                                      relayMessage.time(), true);
    }
  }

  private Runnable createSendToRelayEvent(final Uuid userId,
                                          final Uuid conversationId,
                                          final Uuid messageId) {
    return new Runnable() {
      @Override
      public void run() {
        final User user = view.findUser(userId);
        final Conversation conversation = view.findConversation(conversationId);
        final Message message = view.findMessage(messageId);
        relay.write(id,
                    secret,
                    relay.pack(user.id, user.name, user.creation),
                    relay.pack(conversation.id, conversation.title, conversation.creation),
                    relay.pack(message.id, message.content, message.creation));
      }
    };
  }
}
