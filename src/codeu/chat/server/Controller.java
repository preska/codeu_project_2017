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

import java.util.Collection;

import codeu.chat.common.BasicController;
import codeu.chat.common.Conversation;
import codeu.chat.common.Message;
import codeu.chat.common.RawController;
import codeu.chat.common.Time;
import codeu.chat.common.User;
import codeu.chat.common.Uuid;
import codeu.chat.common.Uuids;
import codeu.chat.util.Logger;

import java.sql.*;

public final class Controller implements RawController, BasicController {

  private final static Logger.Log LOG = Logger.newLog(Controller.class);

  private final Model model;
  private final Uuid.Generator uuidGenerator;

  public Controller(Uuid serverId, Model model) {
    this.model = model;
    this.uuidGenerator = new RandomUuidGenerator(serverId, System.currentTimeMillis());
  }

  @Override
  public Message newMessage(Uuid author, Uuid conversation, String body, boolean databaseAdd) {
    return newMessage(createId(), author, conversation, body, Time.now(), databaseAdd);
  }

  @Override
  public User newUser(String name, String password, boolean databaseAdd) {
      LOG.info("Server making new user %s with password %s", name, password);
      return newUser(createId(), name, password, Time.now(), databaseAdd);
  }

  @Override
  public Conversation newConversation(String title, Uuid owner, boolean databaseAdd) {
    return newConversation(createId(), title, owner, Time.now(), databaseAdd);
  }

  @Override
  public Message newMessage(Uuid id, Uuid author, Uuid conversation, String body, Time creationTime, boolean databaseAdd) {

    final User foundUser = model.userById().first(author);
    final Conversation foundConversation = model.conversationById().first(conversation);

    Message message = null;

    if (foundUser != null && foundConversation != null && isIdFree(id)) {

      message = new Message(id, Uuids.NULL, Uuids.NULL, creationTime, author, body);
      model.add(message);
      LOG.info("Message added: %s", message.id);

      // Find and update the previous "last" message so that it's "next" value
      // will point to the new message.

      if (Uuids.equals(foundConversation.lastMessage, Uuids.NULL)) {

        // The conversation has no messages in it, that's why the last message is NULL (the first
        // message should be NULL too. Since there is no last message, then it is not possible
        // to update the last message's "next" value.

      } else {
        final Message lastMessage = model.messageById().first(foundConversation.lastMessage);
        lastMessage.next = message.id;
      }

      // If the first message points to NULL it means that the conversation was empty and that
      // the first message should be set to the new message. Otherwise the message should
      // not change.

      foundConversation.firstMessage =
          Uuids.equals(foundConversation.firstMessage, Uuids.NULL) ?
          message.id :
          foundConversation.firstMessage;

      // Update the conversation to point to the new last message as it has changed.

      foundConversation.lastMessage = message.id;

      if (!foundConversation.users.contains(foundUser)) {
        foundConversation.users.add(foundUser.id);
      }

        /* Add message to persistent database. */
        if (databaseAdd) {
	        try {
                java.sql.Connection conn = null;
                java.sql.Statement statement = null;
		        java.sql.ResultSet result = null;

	        	Class.forName("org.sqlite.JDBC");
        		conn = DriverManager.getConnection("jdbc:sqlite::test.db");
                conn.setAutoCommit(false);
    		    statement = conn.createStatement();

                String query = "SELECT name FROM sqlite_master WHERE type='table' AND name='[MESSAGES_" + Uuid.toStorableString(foundConversation.id) + "]'";
                result = statement.executeQuery(query);

                /* Create a table to hold the messages for this conversation if it
                 * does not already exist. */
                if (result.next()) {
                    query = "INSERT OR IGNORE INTO [MESSAGES_" + 
                            Uuid.toStorableString(foundConversation.id) +
                            "] (ID, NEXT, PREVIOUS, CREATION, AUTHOR, CONTENT)" +
                            " VALUES ('" + Uuid.toStorableString(message.id) + 
                            "', '" + Uuid.toStorableString(message.next) + 
                            "',  '"  +  Uuid.toStorableString(message.previous) +
                            "', '" + creationTime + "', '" + 
                            Uuid.toStorableString(author) + 
                            "', '" + body + "');";

           	    	statement.executeUpdate(query);
                } else {
                    query = "CREATE table IF NOT EXISTS [MESSAGES_" + 
                            Uuid.toStorableString(foundConversation.id) +
                            "] (ID TEXT PRIMARY KEY       NOT NULL," +
                            "NEXT TEXT                  NOT NULL," +
                            "PREVIOUS TEXT              NOT NULL," +
                            "CREATION TEXT              NOT NULL," +
                            "AUTHOR TEXT                NOT NULL," +
                            "CONTENT TEXT               NOT NULL)";
                    statement.executeUpdate(query);


                    query = "INSERT OR IGNORE INTO [MESSAGES_" + 
                            Uuid.toStorableString(foundConversation.id) +
                            "] (ID, NEXT, PREVIOUS, CREATION, AUTHOR, CONTENT)" +
                            " VALUES ('" + Uuid.toStorableString(message.id) + 
                            "', '" + Uuid.toStorableString(message.next) + 
                            "',  '"  +  Uuid.toStorableString(message.previous) +
                            "', '" + creationTime + "', '" + 
                            Uuid.toStorableString(author) + 
                            "', '" + body + "');";

    	    	    statement.executeUpdate(query);
                }

                statement.close();
    		    conn.commit();
    	    	conn.close();

	        } catch (Exception e) {
		        System.err.println(e.getClass().getName() + ": " + e.getMessage());
		        System.exit(0);
        	}
    	System.out.println("Successfully connected to database and added user data.");
        }

    }

    return message;
  }

  @Override
  public User newUser(Uuid id, String name, String password, Time creationTime,
                      boolean databaseAdd) {

    User user = null;

    if (isIdFree(id)) {

      user = new User(id, name, creationTime);
      model.add(user, password);

      /* Add user to persistent storage. */
      if (databaseAdd) {
        try {
            java.sql.Connection conn = null;
            java.sql.Statement statement = null;

            Class.forName("org.sqlite.JDBC");
            conn = DriverManager.getConnection("jdbc:sqlite::test.db");
            conn.setAutoCommit(false);
            statement = conn.createStatement();
            System.out.println("Right before " + Uuid.toStorableString(user.id));
            String query = "INSERT OR IGNORE INTO USERS " + "VALUES ('" +
                            Uuid.toStorableString(user.id) + "', '" + user.name + 
                            "', " + user.creation.inMs() + ", '" +
                            password + "');";
            System.out.println(query);
            statement.executeUpdate(query);

            statement.close();
            conn.commit();
            conn.close();
        } catch (Exception e) {
            System.err.println(e.getClass().getName() + ": " + e.getMessage());
            System.exit(0);
        }      
      }

      LOG.info(
          "newUser success (user.id=%s user.name=%s user.password=%s user.time=%s)",
          id,
          name,
          password,
          creationTime);

    } else {

      LOG.info(
          "newUser fail - id in use (user.id=%s user.name=%s user.password=%s user.time=%s)",
          id,
          name,
          password,
          creationTime);
    }

    return user;
  }

  @Override
  public Conversation newConversation(Uuid id, String title, Uuid owner, Time creationTime, boolean databaseAdd) {

    final User foundOwner = model.userById().first(owner);

    Conversation conversation = null;

    if (foundOwner != null && isIdFree(id)) {
      conversation = new Conversation(id, owner, creationTime, title);
      model.add(conversation);

        /* Add conversation to persistent storage. */
        if (databaseAdd) {
	        try {
                java.sql.Connection conn = null;
                java.sql.Statement statement = null;
		        java.sql.ResultSet result = null;

	        	Class.forName("org.sqlite.JDBC");
        		conn = DriverManager.getConnection("jdbc:sqlite::test.db");
                conn.setAutoCommit(false);
    		    statement = conn.createStatement();
		        String query = "INSERT INTO CONVERSATIONS (ID, OWNER, CREATION, TITLE) " +
			        		   "VALUES ('" + Uuid.toStorableString(conversation.id) + "', '" 
                                        + Uuid.toStorableString(conversation.owner) + "', " 
				    	    	+ conversation.creation.inMs() + ", '" + conversation.title + "');";
    	    	statement.executeUpdate(query);

                statement.close();
    		    conn.commit();
    	    	conn.close();

	        } catch (Exception e) {
		        System.err.println(e.getClass().getName() + ": " + e.getMessage());
		        System.exit(0);
        	}
        }
    

      LOG.info("Conversation added: " + conversation.id);
    }

    return conversation;
  }

  private Uuid createId() {

    Uuid candidate;

    for (candidate = uuidGenerator.make();
         isIdInUse(candidate);
         candidate = uuidGenerator.make()) {

     // Assuming that "randomUuid" is actually well implemented, this
     // loop should never be needed, but just incase make sure that the
     // Uuid is not actually in use before returning it.

    }

    return candidate;
  }

  private boolean isIdInUse(Uuid id) {
    return model.messageById().first(id) != null ||
           model.conversationById().first(id) != null ||
           model.userById().first(id) != null;
  }

  private boolean isIdFree(Uuid id) { return !isIdInUse(id); }

}
