/*
 * Copyright 2000-2015 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.webdemo.database;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.naming.NamingContext;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.webdemo.ErrorWriter;
import org.jetbrains.webdemo.Project;
import org.jetbrains.webdemo.ProjectFile;
import org.jetbrains.webdemo.ResponseUtils;
import org.jetbrains.webdemo.examples.ExamplesUtils;
import org.jetbrains.webdemo.session.SessionInfo;
import org.jetbrains.webdemo.session.UserInfo;

import javax.naming.InitialContext;
import javax.sql.DataSource;
import java.io.IOException;
import java.math.BigInteger;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

public class MySqlConnector {
    private static final MySqlConnector connector = new MySqlConnector();
    private DataSource dataSource;
    private String databaseUrl;
    private ObjectMapper objectMapper = new ObjectMapper();
    private IdentifierGenerator idGenerator = new IdentifierGenerator();

    private MySqlConnector() {
        try {
            InitialContext initCtx = new InitialContext();
            NamingContext envCtx = (NamingContext) initCtx.lookup("java:comp/env");
            dataSource = (DataSource) envCtx.lookup("jdbc/kotlin");
            Connection connection = dataSource.getConnection();
            databaseUrl = connection.toString();
            ErrorWriter.writeInfoToConsole("Connected to database: " + connection.toString());
            ErrorWriter.getInfoForLog("CONNECT_TO_DATABASE", "-1", "Connected to database: " + databaseUrl);
        } catch (Throwable e) {
            ErrorWriter.ERROR_WRITER.writeExceptionToExceptionAnalyzer(e, SessionInfo.TypeOfRequest.WORK_WITH_DATABASE.name(), "unknown", databaseUrl);
        }
    }

    public static MySqlConnector getInstance() {
        return connector;
    }

    public void addNewUser(UserInfo userInfo) throws DatabaseOperationException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement st = connection.prepareStatement("INSERT INTO users (client_id, provider, username) VALUES (?, ?, ?)")) {
            if (findUser(userInfo)) return;
            st.setString(1, userInfo.getId());
            st.setString(2, userInfo.getType());
            st.setString(3, userInfo.getName());
            st.executeUpdate();
        } catch (SQLException e) {
            throw new DatabaseOperationException(
                    "Can't add user with id:" + userInfo.getId() + " type:" + userInfo.getType() + " name:" + userInfo.getName(),
                    e
            );
        }
    }

    private void closeStatement(PreparedStatement st) {
        try {
            if (st != null) {
                st.close();
            }
        } catch (SQLException e) {
            ErrorWriter.ERROR_WRITER.writeExceptionToExceptionAnalyzer(e, SessionInfo.TypeOfRequest.WORK_WITH_DATABASE.name(), "unknown", "null");
        }
    }

    private boolean findUser(UserInfo userInfo) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement st = connection.prepareStatement("SELECT users.id FROM users WHERE (users.client_id = ? AND users.provider=?)")) {
            st.setString(1, userInfo.getId());
            st.setString(2, userInfo.getType());
            try (ResultSet rs = st.executeQuery()) {
                return rs.next();
            }
        }
    }

    public void saveFile(UserInfo userInfo, ProjectFile file) throws DatabaseOperationException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement st = connection.prepareStatement("UPDATE files JOIN " +
                "projects ON files.project_id = projects.id JOIN " +
                "users ON projects.owner_id = users.id SET" +
                " files.content = ? WHERE" +
                " users.client_id = ? AND users.provider = ? AND files.public_id = ?  ")) {
            st.setString(1, file.getText());
            st.setString(2, userInfo.getId());
            st.setString(3, userInfo.getType());
            st.setString(4, file.getPublicId());
            int rowsUpdated = st.executeUpdate();
            if (rowsUpdated != 1) {
                DatabaseOperationException e = new DatabaseOperationException(rowsUpdated + " files were updated");
                ErrorWriter.ERROR_WRITER.writeExceptionToExceptionAnalyzer(e,
                        SessionInfo.TypeOfRequest.WORK_WITH_DATABASE.name(),
                        "unknown",
                        "user_id " + userInfo.getId() + ", client_type " + userInfo.getType() + ", fileId " + file.getPublicId());
                throw e;
            }
        } catch (SQLException e) {
            if (e.getErrorCode() == 1062) {
                throw new DatabaseOperationException("File with this name already exist in this project", e);
            } else {
                ErrorWriter.ERROR_WRITER.writeExceptionToExceptionAnalyzer(e, SessionInfo.TypeOfRequest.WORK_WITH_DATABASE.name(), "unknown", "Add file " + userInfo.getId() + " " + userInfo.getType() + " " + userInfo.getName() + " " + file.getPublicId() + " " + file.getName());
                throw new DatabaseOperationException("Unknown exception", e);
            }
        }
    }

    private String escape(String str) {
        return str.replaceAll(" ", "%20");
    }

    private String unEscape(String str) {
        return str.replaceAll("%20", " ");
    }

    private boolean checkCountOfFiles(UserInfo userInfo) {
        PreparedStatement st = null;
        ResultSet rs = null;
        try (Connection connection = dataSource.getConnection()) {
            st = connection.prepareStatement("SELECT count(*) FROM files " +
                    "JOIN projects ON projects.id = files.project_id " +
                    "JOIN users ON users.id = projects.owner_id " +
                    "WHERE (users.client_id=? AND users.provider=?)");
            st.setString(1, userInfo.getId());
            st.setString(2, userInfo.getType());
            rs = st.executeQuery();
            if (!rs.next()) {
                return false;
            }
            int count = Integer.parseInt(rs.getString("count(*)"));
            return count < 100;
        } catch (Throwable e) {
            ErrorWriter.ERROR_WRITER.writeExceptionToExceptionAnalyzer(e,
                    SessionInfo.TypeOfRequest.WORK_WITH_DATABASE.name(), "unknown",
                    userInfo.getId() + " " + userInfo.getType() + " " + userInfo.getName());
            return false;
        } finally {
            closeStatementAndResultSet(st, rs);
        }
    }

    private boolean checkCountOfProjects(UserInfo userInfo) {
        PreparedStatement st = null;
        ResultSet rs = null;
        try (Connection connection = dataSource.getConnection()) {
            st = connection.prepareStatement("SELECT count(*) FROM projects " +
                    "JOIN users ON users.id = projects.owner_id " +
                    "WHERE (users.client_id=? AND users.provider=?)");
            st.setString(1, userInfo.getId());
            st.setString(2, userInfo.getType());
            rs = st.executeQuery();
            if (!rs.next()) {
                return false;
            }
            int count = Integer.parseInt(rs.getString("count(*)"));
            return count < 100;
        } catch (Throwable e) {
            ErrorWriter.ERROR_WRITER.writeExceptionToExceptionAnalyzer(e,
                    SessionInfo.TypeOfRequest.WORK_WITH_DATABASE.name(), "unknown",
                    userInfo.getId() + " " + userInfo.getType() + " " + userInfo.getName());
            return false;
        } finally {
            closeStatementAndResultSet(st, rs);
        }
    }


    public void saveProject(UserInfo userInfo, String publicId, Project project) throws DatabaseOperationException {
        int userId = getUserId(userInfo);
        try (Connection connection = dataSource.getConnection();
             PreparedStatement st = connection.prepareStatement(
                "UPDATE projects SET projects.args = ? , projects.run_configuration = ? " +
                        "WHERE projects.owner_id = ?  AND projects.name = ? AND projects.public_id = ?")
        ) {
            st.setString(1, project.args);
            st.setString(2, project.confType);
            st.setString(3, userId + "");
            st.setString(4, escape(project.name));
            st.setString(5, publicId);
            int rowsUpdated = st.executeUpdate();
            if (rowsUpdated != 1) {
                DatabaseOperationException e = new DatabaseOperationException(rowsUpdated + " projects were updated");
                ErrorWriter.ERROR_WRITER.writeExceptionToExceptionAnalyzer(e,
                        SessionInfo.TypeOfRequest.WORK_WITH_DATABASE.name(),
                        "unknown",
                        "user_id " + userInfo.getId() + ", client_type " + userInfo.getType() + ", projectId " + publicId);
                throw e;
            }
        } catch (SQLException e) {
            if (e.getErrorCode() == 1062) {
                throw new DatabaseOperationException("Project with this name already exist", e);
            } else {
                ErrorWriter.ERROR_WRITER.writeExceptionToExceptionAnalyzer(e, SessionInfo.TypeOfRequest.WORK_WITH_DATABASE.name(), "unknown", "Add project " + userInfo.getId() + " " + userInfo.getType() + " " + userInfo.getName() + " " + project.name);
                throw new DatabaseOperationException("Unknown exception", e);
            }
        }
    }


    public String addProject(UserInfo userInfo, String name) throws DatabaseOperationException {
        try {
            String projectId = addProject(userInfo, new Project(name, "", "java"));
            String fileId = addFileToProject(userInfo, projectId, name, "fun main(args: Array<String>) {\n\n}");

            ObjectNode response = new ObjectNode(JsonNodeFactory.instance);
            response.put("projectId", projectId);
            response.put("fileId", fileId);
            return objectMapper.writeValueAsString(response);
        } catch (IOException e) {
            throw new DatabaseOperationException("IO exception");
        }
    }

    public String addProject(UserInfo userInfo, Project project) throws DatabaseOperationException {
        if (!checkCountOfProjects(userInfo)) {
            throw new DatabaseOperationException("You can't save more than 100 projects");
        }
        int userId = getUserId(userInfo);
        PreparedStatement st = null;
        try (Connection connection = dataSource.getConnection()) {
            String publicId = idGenerator.nextProjectId();

            st = connection.prepareStatement("INSERT INTO projects (owner_id, name, args, run_configuration, origin, public_id, read_only_files) VALUES (?,?,?,?,?,?,?) ");
            st.setString(1, userId + "");
            st.setString(2, escape(project.name));
            st.setString(3, project.args);
            st.setString(4, project.confType);
            st.setString(5, project.originUrl);
            st.setString(6, publicId);
            st.setString(7, objectMapper.writeValueAsString(project.readOnlyFileNames));
            st.execute();

            int projectId = getProjectId(userInfo, publicId);
            for (ProjectFile file : project.files) {
                addFileToProject(userInfo, projectId, file.getName(), file.getText());
            }

            return publicId;
        } catch (SQLException e) {
            if (e.getErrorCode() == 1062) {
                throw new DatabaseOperationException("Project with this name already exist", e);
            } else {
                ErrorWriter.ERROR_WRITER.writeExceptionToExceptionAnalyzer(e, SessionInfo.TypeOfRequest.WORK_WITH_DATABASE.name(), "unknown", "Add project " + userInfo.getId() + " " + userInfo.getType() + " " + userInfo.getName() + " " + project.name);
                throw new DatabaseOperationException("Unknown exception", e);
            }
        } catch (Throwable e) {
            ErrorWriter.ERROR_WRITER.writeExceptionToExceptionAnalyzer(e, SessionInfo.TypeOfRequest.WORK_WITH_DATABASE.name(), "unknown", "Add project " + userInfo.getId() + " " + userInfo.getType() + " " + userInfo.getName() + " " + project.name);
            throw new DatabaseOperationException("Unknown exception", e);
        } finally {
            closeStatement(st);
        }
    }

    public String addFileToProject(UserInfo userInfo, String projectPublicId, String fileName) throws DatabaseOperationException {
        return addFileToProject(userInfo, getProjectId(userInfo, projectPublicId), fileName, "");
    }

    public String addFileToProject(UserInfo userInfo, String projectPublicId, String fileName, String content) throws DatabaseOperationException {
        return addFileToProject(userInfo, getProjectId(userInfo, projectPublicId), fileName, content);
    }

    private String addFileToProject(UserInfo userInfo, int projectId, String fileName, String content) throws DatabaseOperationException {
        if (!checkCountOfFiles(userInfo)) {
            throw new DatabaseOperationException("You can't save more than 100 files");
        }
        fileName = escape(fileName.endsWith(".kt") ? fileName : fileName + ".kt");
        try (Connection connection = dataSource.getConnection();
             PreparedStatement st = connection.prepareStatement("INSERT INTO files (project_id, public_id, name, content) VALUES (?,?,?,?) ")) {
            String publicId = idGenerator.nextFileId();

            st.setString(1, projectId + "");
            st.setString(2, publicId);
            st.setString(3, fileName);
            st.setString(4, content);
            st.execute();

            return publicId;
        } catch (SQLException e) {
            if (e.getErrorCode() == 1062) {
                throw new DatabaseOperationException("File with this name already exist in this project", e);
            } else {
                ErrorWriter.ERROR_WRITER.writeExceptionToExceptionAnalyzer(e, SessionInfo.TypeOfRequest.WORK_WITH_DATABASE.name(), "unknown", "Add file " + userInfo.getId() + " " + userInfo.getType() + " " + userInfo.getName() + " " + projectId + " " + fileName);
                throw new DatabaseOperationException("Unknown exception", e);
            }
        } catch (Throwable e) {
            ErrorWriter.ERROR_WRITER.writeExceptionToExceptionAnalyzer(e, SessionInfo.TypeOfRequest.WORK_WITH_DATABASE.name(), "unknown", "Add file " + userInfo.getId() + " " + userInfo.getType() + " " + userInfo.getName() + " " + projectId + " " + fileName);
            throw new DatabaseOperationException("Unknown exception", e);
        }
    }

    public ArrayNode getProjectHeaders(UserInfo userInfo) throws DatabaseOperationException {
        PreparedStatement st = null;
        ResultSet rs = null;
        try (Connection connection = dataSource.getConnection()) {
            st = connection.prepareStatement(
                    "SELECT projects.public_id, projects.name FROM projects JOIN " +
                            "users ON projects.owner_id = users.id WHERE " +
                            "(users.client_id = ? AND users.provider = ?)"
            );
            st.setString(1, userInfo.getId());
            st.setString(2, userInfo.getType());

            rs = st.executeQuery();

            ArrayNode projects = new ArrayNode(JsonNodeFactory.instance);
            while (rs.next()) {
                ObjectNode object = new ObjectNode(JsonNodeFactory.instance);
                object.put("name", unEscape(rs.getString("name")));
                object.put("publicId", rs.getString("public_id"));
                projects.add(object);
            }

            return projects;
        } catch (Throwable e) {
            ErrorWriter.ERROR_WRITER.writeExceptionToExceptionAnalyzer(e,
                    SessionInfo.TypeOfRequest.WORK_WITH_DATABASE.name(), "unknown",
                    userInfo.getId() + " " + userInfo.getType() + " " + userInfo.getName());
            throw new DatabaseOperationException("Unknown error while loading list of your programs");
        } finally {
            closeStatementAndResultSet(st, rs);
        }
    }


    public String getProjectContent(String id) throws DatabaseOperationException {
        PreparedStatement st = null;
        ResultSet rs = null;
        try (Connection connection = dataSource.getConnection()) {
            st = connection.prepareStatement(
                    "SELECT * FROM projects WHERE projects.public_id = ?");
            st.setString(1, id);
            rs = st.executeQuery();

            if (rs.next()) {
                List<String> readOnlyFileNames;
                if (rs.getString("read_only_files") == null || rs.getString("read_only_files").equals("")) {
                    readOnlyFileNames = new ArrayList<>();
                } else {
                    readOnlyFileNames = objectMapper.readValue(rs.getString("read_only_files"), List.class);
                }
                Project project = new Project(
                        id,
                        unEscape(rs.getString("name")),
                        rs.getString("args"),
                        rs.getString("run_configuration"),
                        rs.getString("origin"),
                        readOnlyFileNames
                );
                ExamplesUtils.addUnmodifiableFilesToProject(project);

                st = connection.prepareStatement("SELECT * FROM files WHERE project_id = ?");
                st.setString(1, rs.getInt("id") + "");
                rs = st.executeQuery();
                while (rs.next()) {
                    ProjectFile file = new ProjectFile(unEscape(rs.getString("name")), rs.getString("content"), true, rs.getString("public_id"), ProjectFile.Type.KOTLIN_FILE);
                    project.files.add(file);
                }
                return objectMapper.writeValueAsString(project);
            } else {
                return null;
            }
        } catch (Throwable e) {
            ErrorWriter.ERROR_WRITER.writeExceptionToExceptionAnalyzer(e, SessionInfo.TypeOfRequest.WORK_WITH_DATABASE.name(), "unknown", id);
            return ResponseUtils.getErrorInJson("Unknown error while loading your project");
        } finally {
            closeStatementAndResultSet(st, rs);
        }
    }

    public boolean isProjectExists(String publicId) throws DatabaseOperationException {
        PreparedStatement st = null;
        ResultSet rs = null;
        try (Connection connection = dataSource.getConnection()) {
            st = connection.prepareStatement(
                    "SELECT projects.id FROM projects WHERE projects.public_id = ?");
            st.setString(1, publicId);
            rs = st.executeQuery();
            if (rs.next()) {
                return true;
            } else {
                return false;
            }

        } catch (Throwable e) {
            ErrorWriter.ERROR_WRITER.writeExceptionToExceptionAnalyzer(e, SessionInfo.TypeOfRequest.WORK_WITH_DATABASE.name(), "unknown", publicId);
            throw new DatabaseOperationException("Unknown exception");
        } finally {
            closeStatementAndResultSet(st, rs);
        }
    }

    private void closeStatementAndResultSet(PreparedStatement st, ResultSet rs) {
        try {
            if (st != null) {
                st.close();
            }
            if (rs != null) {
                rs.close();
            }
        } catch (SQLException e) {
            ErrorWriter.ERROR_WRITER.writeExceptionToExceptionAnalyzer(e, SessionInfo.TypeOfRequest.WORK_WITH_DATABASE.name(), "unknown", "null");
        }
    }

    public void deleteFile(UserInfo userInfo, String publicId) throws DatabaseOperationException {
        try (
                Connection connection = dataSource.getConnection();
                PreparedStatement st = connection.prepareStatement("DELETE files.* FROM files JOIN" +
                " projects ON files.project_id = projects.id JOIN " +
                " users ON projects.owner_id = users.id WHERE " +
                        " users.client_id = ? AND users.provider  = ? AND files.public_id = ?")
        ) {
            st.setString(1, userInfo.getId());
            st.setString(2, userInfo.getType());
            st.setString(3, publicId);
            int rowsDeleted = st.executeUpdate();
            if (rowsDeleted != 1) {
                DatabaseOperationException e = new DatabaseOperationException(rowsDeleted + " files were deleted");
                ErrorWriter.ERROR_WRITER.writeExceptionToExceptionAnalyzer(e,
                        SessionInfo.TypeOfRequest.WORK_WITH_DATABASE.name(),
                        "unknown",
                        "user_id " + userInfo.getId() + ", client_type " + userInfo.getType() + ", fileId " + publicId);
                throw e;
            }
        } catch (Throwable e) {
            ErrorWriter.ERROR_WRITER.writeExceptionToExceptionAnalyzer(e, SessionInfo.TypeOfRequest.WORK_WITH_DATABASE.name(), "unknown", userInfo.getId() + " " + userInfo.getType() + " " + userInfo.getName() + " " + publicId);
            throw new DatabaseOperationException("Unknown exception ", e);
        }

    }

    public void deleteUnmodifiableFile(UserInfo userInfo, String fileName, String projectId) throws DatabaseOperationException {
        PreparedStatement st = null;
        ResultSet rs = null;
        try (Connection connection = dataSource.getConnection()) {
            st = connection.prepareStatement("SELECT read_only_files FROM projects " +
                    "JOIN users ON users.id = projects.owner_id " +
                    "WHERE users.client_id = ? AND users.provider = ? AND projects.public_id = ?");
            st.setString(1, userInfo.getId());
            st.setString(2, userInfo.getType());
            st.setString(3, projectId);
            rs = st.executeQuery();
            if (rs.next()) {
                List<String> readOnlyFileNames = objectMapper.readValue(rs.getString("read_only_files"), List.class);
                if (readOnlyFileNames != null) {
                    if (!readOnlyFileNames.contains(fileName)) {
                        DatabaseOperationException e = new DatabaseOperationException("Can't find read-only file");
                        ErrorWriter.ERROR_WRITER.writeExceptionToExceptionAnalyzer(e,
                                SessionInfo.TypeOfRequest.WORK_WITH_DATABASE.name(),
                                "unknown",
                                "user_id " + userInfo.getId() + ", client_type " + userInfo.getType() + ", projectId " + projectId + ", fileName" + fileName);
                        throw e;
                    }
                    readOnlyFileNames.remove(fileName);
                }
                st = connection.prepareStatement("UPDATE projects SET read_only_files=? WHERE projects.public_id = ?");
                st.setString(1, objectMapper.writeValueAsString(readOnlyFileNames));
                st.setString(2, projectId);
                int rowsUpdated = st.executeUpdate();
                if (rowsUpdated != 1) {
                    DatabaseOperationException e = new DatabaseOperationException(rowsUpdated + " projects were updated");
                    ErrorWriter.ERROR_WRITER.writeExceptionToExceptionAnalyzer(e,
                            SessionInfo.TypeOfRequest.WORK_WITH_DATABASE.name(),
                            "unknown",
                            "user_id " + userInfo.getId() + ", client_type " + userInfo.getType() + ", projectId " + projectId + ", fileName" + fileName);
                    throw e;
                }
            }
        } catch (SQLException e) {
            ErrorWriter.ERROR_WRITER.writeExceptionToExceptionAnalyzer(e, SessionInfo.TypeOfRequest.WORK_WITH_DATABASE.name(), "unknown", userInfo.getId() + " " + userInfo.getType() + " " + userInfo.getName() + " " + fileName);
            throw new DatabaseOperationException("Unknown exception ", e);
        } catch (IOException e) {
            ErrorWriter.ERROR_WRITER.writeExceptionToExceptionAnalyzer(e, SessionInfo.TypeOfRequest.WORK_WITH_DATABASE.name(), "unknown", userInfo.getId() + " " + userInfo.getType() + " " + userInfo.getName() + " " + fileName);
            throw new DatabaseOperationException("Unknown exception ", e);
        } finally {
            closeStatementAndResultSet(st, rs);
        }

    }


    public void renameFile(UserInfo userInfo, String publicId, String newName) throws DatabaseOperationException {
        try (
                Connection connection = dataSource.getConnection();
                PreparedStatement st = connection.prepareStatement("UPDATE files JOIN " +
                "projects ON files.project_id = projects.id JOIN " +
                "users ON projects.owner_id = users.id SET " +
                "files.name = ? WHERE " +
                        "users.client_id = ? AND  users.provider = ? AND files.public_id = ?")
        ) {
            st.setString(1, escape(newName));
            st.setString(2, userInfo.getId());
            st.setString(3, userInfo.getType());
            st.setString(4, publicId);
            int rowsUpdated = st.executeUpdate();
            if (rowsUpdated != 1) {
                DatabaseOperationException e = new DatabaseOperationException(rowsUpdated + " files were updated");
                ErrorWriter.ERROR_WRITER.writeExceptionToExceptionAnalyzer(e,
                        SessionInfo.TypeOfRequest.WORK_WITH_DATABASE.name(),
                        "unknown",
                        "user_id " + userInfo.getId() + ", client_type " + userInfo.getType() + ", newName " + newName + ", fileId" + publicId);
                throw e;
            }
        } catch (SQLException e) {
            if (e.getErrorCode() == 1062) {
                throw new DatabaseOperationException("File with this name already exist in this project", e);
            } else {
                ErrorWriter.ERROR_WRITER.writeExceptionToExceptionAnalyzer(e, SessionInfo.TypeOfRequest.WORK_WITH_DATABASE.name(), "unknown", "Rename file " + userInfo.getId() + " " + userInfo.getType() + " " + userInfo.getName() + " " + publicId);
                throw new DatabaseOperationException("Unknown exception", e);
            }
        }
    }


    public void deleteProject(UserInfo userInfo, String publicId) throws DatabaseOperationException {
        int userId = getUserId(userInfo);
        try (
                Connection connection = dataSource.getConnection();
                PreparedStatement st = connection.prepareStatement("DELETE FROM projects WHERE projects.owner_id = ? AND projects.public_id = ?")
        ) {
            st.setString(1, userId + "");
            st.setString(2, publicId);
            int rowsDeleted = st.executeUpdate();
            if (rowsDeleted != 1) {
                DatabaseOperationException e = new DatabaseOperationException(rowsDeleted + " projects were deleted");
                ErrorWriter.ERROR_WRITER.writeExceptionToExceptionAnalyzer(e,
                        SessionInfo.TypeOfRequest.WORK_WITH_DATABASE.name(),
                        "unknown",
                        "user_id " + userInfo.getId() + ", client_type " + userInfo.getType() + ", projectId " + publicId);
                throw e;
            }
        } catch (Throwable e) {
            ErrorWriter.ERROR_WRITER.writeExceptionToExceptionAnalyzer(e, SessionInfo.TypeOfRequest.WORK_WITH_DATABASE.name(), "unknown", "Delete project " + userInfo.getId() + " " + userInfo.getType() + " " + userInfo.getName() + " " + publicId);
            throw new DatabaseOperationException("Unknown exception");
        }

    }

    public void renameProject(UserInfo userInfo, String publicId, String newName) throws DatabaseOperationException {
        int userId = getUserId(userInfo);
        try (
                Connection connection = dataSource.getConnection();
                PreparedStatement st = connection.prepareStatement("UPDATE projects SET projects.name = ? WHERE projects.public_id =? AND projects.owner_id = ?")
        ) {
            st.setString(1, escape(newName));
            st.setString(2, publicId);
            st.setString(3, userId + "");
            int rowsUpdated = st.executeUpdate();
            if (rowsUpdated != 1) {
                DatabaseOperationException e = new DatabaseOperationException(rowsUpdated + " projects were updated");
                ErrorWriter.ERROR_WRITER.writeExceptionToExceptionAnalyzer(e,
                        SessionInfo.TypeOfRequest.WORK_WITH_DATABASE.name(),
                        "unknown",
                        "user_id " + userInfo.getId() + ", client_type " + userInfo.getType() + ", newName " + newName + ", projectId" + publicId);
                throw e;
            }
        } catch (SQLException e) {
            if (e.getErrorCode() == 1062) {
                throw new DatabaseOperationException("Project with this name already exist", e);
            } else {
                ErrorWriter.ERROR_WRITER.writeExceptionToExceptionAnalyzer(e, SessionInfo.TypeOfRequest.WORK_WITH_DATABASE.name(), "unknown", "Rename project " + userInfo.getId() + " " + userInfo.getType() + " " + userInfo.getName() + " " + publicId);
                throw new DatabaseOperationException("Unknown exception", e);
            }
        } catch (Throwable e) {
            ErrorWriter.ERROR_WRITER.writeExceptionToExceptionAnalyzer(e, SessionInfo.TypeOfRequest.WORK_WITH_DATABASE.name(), "unknown", "Rename project " + userInfo.getId() + " " + userInfo.getType() + " " + userInfo.getName() + " " + publicId + " ");
            throw new DatabaseOperationException("Unknown exception", e);
        }
    }

    private int getUserId(UserInfo userInfo) throws DatabaseOperationException {
        try (
                Connection connection = dataSource.getConnection();
                PreparedStatement st = connection.prepareStatement("SELECT users.id FROM users WHERE (users.client_id = ? AND users.provider=?)")
        ) {
            st.setString(1, userInfo.getId());
            st.setString(2, userInfo.getType());
            try (ResultSet rs = st.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt("id");
                } else {
                    throw new DatabaseOperationException("User with id" + userInfo.getId() + " don't exist");
                }
            }
        } catch (SQLException e) {
            ErrorWriter.ERROR_WRITER.writeExceptionToExceptionAnalyzer(e,
                    SessionInfo.TypeOfRequest.WORK_WITH_DATABASE.name(), "unknown",
                    userInfo.getId() + " " + userInfo.getType() + " " + userInfo.getName());
            throw new DatabaseOperationException("Unknown exception", e);
        }
    }

    private int getProjectId(UserInfo userInfo, String publicId) throws DatabaseOperationException {
        try (
                Connection connection = dataSource.getConnection();
                PreparedStatement st = connection.prepareStatement(
                "SELECT projects.id FROM projects JOIN " +
                        "users ON projects.owner_id =users.id WHERE " +
                        "( users.client_id = ? AND  users.provider = ? AND projects.public_id = ?)")
        ) {
            st.setString(1, userInfo.getId());
            st.setString(2, userInfo.getType());
            st.setString(3, publicId);
            try (ResultSet rs = st.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt("id");
                } else {
                    throw new DatabaseOperationException("Project with this name don't exist");
                }
            }
        } catch (Throwable e) {
            ErrorWriter.ERROR_WRITER.writeExceptionToExceptionAnalyzer(e,
                    SessionInfo.TypeOfRequest.WORK_WITH_DATABASE.name(), "unknown",
                    userInfo.getId() + " " + userInfo.getType() + " " + userInfo.getName());
            throw new DatabaseOperationException("UnknownException", e);
        }
    }

    @Nullable
    public String getProjectNameById(String projectId) throws DatabaseOperationException {
        PreparedStatement st = null;
        ResultSet rs = null;
        try (Connection connection = dataSource.getConnection()) {
            st = connection.prepareStatement(
                    "SELECT projects.name FROM projects WHERE " +
                    "projects.public_id = ?"
            );
            st.setString(1, projectId);
            rs = st.executeQuery();
            if (rs.next()) {
                return unEscape(rs.getString("name"));
            } else {
                return null;
            }
        } catch (Exception e) {
            throw new DatabaseOperationException("Unknown exception");
        } finally {
            closeStatementAndResultSet(st, rs);
        }
    }


    public ProjectFile getFile(String publicId) throws DatabaseOperationException {
        PreparedStatement st = null;
        ResultSet rs = null;
        try (Connection connection = dataSource.getConnection()) {
            st = connection.prepareStatement("SELECT * FROM files WHERE files.public_id = ?");
            st.setString(1, publicId);
            st.execute();
            rs = st.executeQuery();
            if (rs.next()) {
                String name = rs.getString("name");
                String content = rs.getString("content");
                return new ProjectFile(name, content, true, publicId, ProjectFile.Type.KOTLIN_FILE);
            } else {
                return null;
            }
        } catch (Throwable e) {
            ErrorWriter.ERROR_WRITER.writeExceptionToExceptionAnalyzer(e, SessionInfo.TypeOfRequest.WORK_WITH_DATABASE.name(), "unknown", "Get file " + publicId);
            throw new DatabaseOperationException("Unknown exception", e);
        } finally {
            closeStatementAndResultSet(st, rs);
        }
    }

    private final class IdentifierGenerator {
        private SecureRandom random = new SecureRandom();

        private String nextId() {
            return new BigInteger(130, random).toString(32);
        }

        public String nextProjectId() throws SQLException {
            while (true) {
                String id = nextId();
                PreparedStatement st = null;
                ResultSet rs = null;
                try (Connection connection = dataSource.getConnection()) {
                    st = connection.prepareStatement(
                            "SELECT COUNT(projects.public_id) FROM projects WHERE public_id = ?"
                    );
                    st.setString(1, id);
                    rs = st.executeQuery();
                    rs.next();
                    int numberOfRows = rs.getInt(1);
                    if (numberOfRows == 0) return id;
                } finally {
                    closeStatementAndResultSet(st, rs);
                }
            }
        }

        public String nextFileId() throws SQLException {
            while (true) {
                String id = nextId();
                PreparedStatement st = null;
                ResultSet rs = null;
                try (Connection connection = dataSource.getConnection()) {
                    st = connection.prepareStatement(
                            "SELECT COUNT(files.public_id) FROM files WHERE public_id = ?"
                    );
                    st.setString(1, id);
                    rs = st.executeQuery();
                    rs.next();
                    int numberOfRows = rs.getInt(1);
                    if (numberOfRows == 0) return id;
                } finally {
                    closeStatementAndResultSet(st, rs);
                }
            }
        }
    }
}
