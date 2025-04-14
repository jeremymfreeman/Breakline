// RunnerGame.java
// This file has been cleaned to remove non-ASCII characters.
// Ensure you save it with UTF-8 encoding.

import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.BufferUtils;
import org.lwjgl.stb.STBEasyFont;

import javax.sound.sampled.*;
import java.io.*;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class RunnerGame {
    // Window settings
    private long window;
    private int width = 800;
    private int height = 600;

    // Game objects and state
    private Player localPlayer;         // For host-controlled player or single-player
    private RemotePlayer remotePlayer;    // For client-controlled player (or host view of client)
    // Single-player mode:
    private Terrain terrain;
    private List<Obstacle> obstacles = new ArrayList<>();

    // Multiplayer mode: separate racetracks and obstacle lists.
    private Terrain localTerrain, remoteTerrain;
    private List<Obstacle> localObstacles = new ArrayList<>();
    private List<Obstacle> remoteObstacles = new ArrayList<>();

    private Random random = new Random();

    // Movement, scoring, and obstacle spawn timing
    private float forwardSpeed = 5.0f;
    private int localScore = 0;
    private int remoteScore = 0;
    private long lastObstacleTime = 0;           // for single-player
    private long lastLocalObstacleTime = 0;        // for host/local track
    private long lastRemoteObstacleTime = 0;       // for client/remote track

    // Game-over flags
    private boolean localGameOver = false;
    private boolean remoteGameOver = false;

    // Start screen flag -- simulation does not proceed until gameStarted is set true.
    private boolean gameStarted = false;

    // Multiplayer and networking flags
    private NetworkManager networkManager;
    private boolean isMultiplayer = false;
    private boolean isHost = false;  // true if running as host (split-screen)

    // To prevent multiple clicks on game-over menu buttons
    private boolean gameOverButtonClicked = false;

    // Model for obstacles
    private Model obstacleModel;

    // Main method
    public static void main(String[] args) {
        boolean isHost = false;
        String serverAddress = "localhost";
        if (args.length > 0) {
            if (args[0].equalsIgnoreCase("host")) {
                isHost = true;
            } else {
                serverAddress = args[0];
            }
        }
        new RunnerGame(isHost).run(serverAddress);
    }

    // Constructor accepts the host flag.
    public RunnerGame(boolean isHost) {
        this.isHost = isHost;
        isMultiplayer = true; // For this example, we always use networking (multiplayer mode)
    }

    // Sound playback method
    Clip musicClip;

    public void playMusic(String path) {
        try {
            if (musicClip != null && musicClip.isRunning()) return; // prevent overlapping
            AudioInputStream audioStream = AudioSystem.getAudioInputStream(new File(path));
            musicClip = AudioSystem.getClip();
            musicClip.open(audioStream);
            musicClip.loop(Clip.LOOP_CONTINUOUSLY); // loop forever
            musicClip.start();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static void playSoundEffect(String path) {
        new Thread(() -> {
            try {
                AudioInputStream audioStream = AudioSystem.getAudioInputStream(new File(path));
                Clip clip = AudioSystem.getClip();
                clip.open(audioStream);
                clip.start();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start(); // play without blocking the game loop
    }

    // Main game loop
    public void run(String serverAddress) {
        init(serverAddress);
        loop();
        GLFW.glfwDestroyWindow(window);
        GLFW.glfwTerminate();
        if (networkManager != null)
            networkManager.stop();
    }

    private void init(String serverAddress) {
        if (!GLFW.glfwInit())
            throw new IllegalArgumentException("Unable to initialize GLFW");

        window = GLFW.glfwCreateWindow(width, height, "Runner Game - Multiplayer", 0, 0);
        if (window == 0)
            throw new RuntimeException("Failed to create the GLFW window");
        GLFW.glfwMakeContextCurrent(window);
        GL.createCapabilities();

        // Set a distinct clear color for the background.
        GL11.glClearColor(0.2f, 0.2f, 0.2f, 1.0f);

        // Setup initial perspective projection.
        GL11.glMatrixMode(GL11.GL_PROJECTION);
        GL11.glLoadIdentity();
        setPerspectiveProjection(45.0f, (float) width / height, 0.1f, 100.0f);
        GL11.glMatrixMode(GL11.GL_MODELVIEW);

        // Initialize lighting and material.
        initLighting();
        GL11.glEnable(GL11.GL_COLOR_MATERIAL);
        GL11.glColorMaterial(GL11.GL_FRONT_AND_BACK, GL11.GL_AMBIENT_AND_DIFFUSE);
        FloatBuffer lightPosition = BufferUtils.createFloatBuffer(4);
        lightPosition.put(new float[]{0.0f, 10.0f, 10.0f, 1.0f});
        lightPosition.flip();
        GL11.glLightfv(GL11.GL_LIGHT0, GL11.GL_POSITION, lightPosition);

        GL11.glEnable(GL11.GL_DEPTH_TEST);
        GL11.glDepthFunc(GL11.GL_LEQUAL);
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);

        // Load obstacle model.
        try {
            obstacleModel = OBJLoader.loadModel("assets/obstacle.obj");
        } catch (IOException e) {
            System.err.println("Failed to load obstacle OBJ file: " + e.getMessage());
            e.printStackTrace();
        }
        if (obstacleModel == null) {
            System.out.println("Using fallback cube model for obstacles.");
            obstacleModel = createFallbackCube();
        }

        // Initialize players.
        localPlayer = new Player(0, 0, 10.0f);
        remotePlayer = new RemotePlayer(0, 0, 10.0f);
        // When running as a client (non-host), set the controlled (remote) player to red.
        if (!isHost) {
            remotePlayer.setColor(1.0f, 0.0f, 0.0f);
        }

        // Create terrain and obstacle lists.
        if (isMultiplayer) {
            localTerrain = new Terrain();
            remoteTerrain = new Terrain();
            localObstacles = new ArrayList<>();
            remoteObstacles = new ArrayList<>();
        } else {
            terrain = new Terrain();
            obstacles = new ArrayList<>();
        }

        // Setup networking (host and client).
        try {
            networkManager = new NetworkManager(serverAddress, 12345, remotePlayer);
        } catch (IOException e) {
            e.printStackTrace();
        }

        long currentTime = System.currentTimeMillis();
        lastObstacleTime = currentTime;
        lastLocalObstacleTime = currentTime;
        lastRemoteObstacleTime = currentTime;
    }

    private Model createFallbackCube() {
        // Return a simple cube model if the OBJ cannot be loaded.
        // For simplicity, we return null and rely on the obstacle render fallback.
        return null;
    }

    private void loop() {
        long lastTime = System.currentTimeMillis();
        while (!GLFW.glfwWindowShouldClose(window)) {
            long currentTime = System.currentTimeMillis();
            float deltaTime = (currentTime - lastTime) / 1000.0f;
            lastTime = currentTime;

            // Pause simulation until game is started via the start screen.
            if (!gameStarted) {
                System.out.println("Rendering start screen. Waiting for click...");
                renderStartScreen();
                GLFW.glfwSwapBuffers(window);
                GLFW.glfwPollEvents();
                continue;
            }

            // If the controlled player has lost all 3 lives, show game-over screen.
            if ((isMultiplayer && ((isHost && localGameOver) || (!isHost && remoteGameOver))) ||
                    (!isMultiplayer && localGameOver)) {
                renderGameOver("Game Over!");
                GLFW.glfwSwapBuffers(window);
                GLFW.glfwPollEvents();
                continue;
            }

            GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
            GL11.glLoadIdentity();

            // Update player movement.
            updatePlayerMovement(deltaTime);

            // Spawn obstacles using separate timers in multiplayer.
            if (isMultiplayer) {
                if (isHost) {
                    spawnLocalObstacles();
                    spawnRemoteObstacles();
                } else {
                    spawnRemoteObstacles();
                }
            } else {
                spawnObstacles();
            }

            // Check collisions and update scores.
            if (isMultiplayer) {
                checkLocalCollisions();
                checkRemoteCollisions();
            } else {
                checkLocalCollisions();
            }
            updateScore();

            // Send the controlled player's position over the network.
            if (networkManager != null) {
                String updateMsg;
                if (isHost)
                    updateMsg = String.format("%f,%f,%f", localPlayer.getX(), localPlayer.getY(), localPlayer.getZ());
                else
                    updateMsg = String.format("%f,%f,%f", remotePlayer.getX(), remotePlayer.getY(), remotePlayer.getZ());
                networkManager.send(updateMsg);
            }

            // --- Rendering the scene ---
            if (isMultiplayer) {
                if (isHost) {
                    // Host split-screen.
                    // Left half: local track.
                    GL11.glViewport(0, 0, width / 2, height);
                    GL11.glMatrixMode(GL11.GL_PROJECTION);
                    GL11.glLoadIdentity();
                    setPerspectiveProjection(45.0f, (float)(width / 2)/height, 0.1f, 100.0f);
                    GL11.glMatrixMode(GL11.GL_MODELVIEW);
                    updateCameraForPlayer(localPlayer);
                    localTerrain.update(localPlayer.getZ() - 10);
                    localTerrain.render();
                    localPlayer.render();
                    for (Obstacle o : localObstacles) {
                        o.render();
                    }
                    renderHUDForPlayer(localPlayer, 0, localScore);

                    // Right half: remote track.
                    GL11.glViewport(width / 2, 0, width / 2, height);
                    GL11.glMatrixMode(GL11.GL_PROJECTION);
                    GL11.glLoadIdentity();
                    setPerspectiveProjection(45.0f, (float)(width / 2)/height, 0.1f, 100.0f);
                    GL11.glMatrixMode(GL11.GL_MODELVIEW);
                    updateCameraForPlayer(remotePlayer);
                    remoteTerrain.update(remotePlayer.getZ() - 10);
                    remoteTerrain.render();
                    remotePlayer.render();
                    for (Obstacle o : remoteObstacles) {
                        o.render();
                    }
                    renderHUDForPlayer(remotePlayer, 0, remoteScore);

                    // Reset viewport to full window.
                    GL11.glViewport(0, 0, width, height);
                } else {
                    // Client: run only its own track full-screen.
                    GL11.glViewport(0, 0, width, height);
                    GL11.glMatrixMode(GL11.GL_PROJECTION);
                    GL11.glLoadIdentity();
                    setPerspectiveProjection(45.0f, (float)width/height, 0.1f, 100.0f);
                    GL11.glMatrixMode(GL11.GL_MODELVIEW);
                    updateCameraForPlayer(remotePlayer);
                    remoteTerrain.update(remotePlayer.getZ() - 10);
                    remoteTerrain.render();
                    remotePlayer.render();
                    for (Obstacle o : remoteObstacles) {
                        o.render();
                    }
                    renderHUDForPlayer(remotePlayer, 0, remoteScore);
                }
            } else {
                // Single-player.
                GL11.glViewport(0, 0, width, height);
                updateCameraForPlayer(localPlayer);
                terrain.update(localPlayer.getZ() - 10);
                terrain.render();
                localPlayer.render();
                for (Obstacle o : obstacles) {
                    o.render();
                }
                renderHUDForPlayer(localPlayer, 0, localScore);
            }

            GLFW.glfwSwapBuffers(window);
            GLFW.glfwPollEvents();
        }
    }

    // ----- Render the Start Screen -----
    private void renderStartScreen() {
        GL11.glMatrixMode(GL11.GL_PROJECTION);
        GL11.glPushMatrix();
        GL11.glLoadIdentity();
        playMusic("music.wav");
        GL11.glOrtho(0, width, height, 0, -1, 1); // 2D orthographic projection
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        GL11.glPushMatrix();
        GL11.glLoadIdentity();

        // Draw semi-transparent overlay.
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glColor4f(0, 0, 0, 0.7f);
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glVertex2f(0, 0);
        GL11.glVertex2f(width, 0);
        GL11.glVertex2f(width, height);
        GL11.glVertex2f(0, height);
        GL11.glEnd();

        // Draw "START" button.
        int buttonWidth = 100, buttonHeight = 30;
        float buttonX = width / 2 - buttonWidth / 2;
        float buttonY = height / 2 - buttonHeight / 2;
        GL11.glColor3f(0, 0, 1);
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glVertex2f(buttonX, buttonY);
        GL11.glVertex2f(buttonX + buttonWidth, buttonY);
        GL11.glVertex2f(buttonX + buttonWidth, buttonY + buttonHeight);
        GL11.glVertex2f(buttonX, buttonY + buttonHeight);
        GL11.glEnd();
        GL11.glColor3f(1, 1, 1);
        GL11.glBegin(GL11.GL_LINE_LOOP);
        GL11.glVertex2f(buttonX, buttonY);
        GL11.glVertex2f(buttonX + buttonWidth, buttonY);
        GL11.glVertex2f(buttonX + buttonWidth, buttonY + buttonHeight);
        GL11.glVertex2f(buttonX, buttonY + buttonHeight);
        GL11.glEnd();

        // Draw "START" text.
        GL11.glColor3f(1, 1, 1);
        drawText2D(buttonX + 25, buttonY + 20, "START");

        GL11.glDisable(GL11.GL_BLEND);
        GL11.glPopMatrix();
        GL11.glMatrixMode(GL11.GL_PROJECTION);
        GL11.glPopMatrix();
        GL11.glMatrixMode(GL11.GL_MODELVIEW);

        // Check for button click.
        if (GLFW.glfwGetMouseButton(window, GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS) {
            double[] xpos = new double[1], ypos = new double[1];
            GLFW.glfwGetCursorPos(window, xpos, ypos);
            if (xpos[0] >= buttonX && xpos[0] <= buttonX + buttonWidth &&
                    ypos[0] >= buttonY && ypos[0] <= buttonY + buttonHeight) {
                System.out.println("Start button clicked.");
                gameStarted = true;
            }
        }
    }

    // ----- Update Player Movement -----
    private void updatePlayerMovement(float deltaTime) {
        if (isMultiplayer) {
            if (isHost) {
                if (GLFW.glfwGetKey(window, GLFW.GLFW_KEY_LEFT) == GLFW.GLFW_PRESS)
                    localPlayer.moveLeft(deltaTime);
                if (GLFW.glfwGetKey(window, GLFW.GLFW_KEY_RIGHT) == GLFW.GLFW_PRESS)
                    localPlayer.moveRight(deltaTime);
                if (GLFW.glfwGetKey(window, GLFW.GLFW_KEY_SPACE) == GLFW.GLFW_PRESS)
                    localPlayer.jump();
                localPlayer.update(deltaTime);
                localPlayer.advance(forwardSpeed * deltaTime);
            } else {
                if (GLFW.glfwGetKey(window, GLFW.GLFW_KEY_LEFT) == GLFW.GLFW_PRESS)
                    remotePlayer.moveLeft(deltaTime);
                if (GLFW.glfwGetKey(window, GLFW.GLFW_KEY_RIGHT) == GLFW.GLFW_PRESS)
                    remotePlayer.moveRight(deltaTime);
                if (GLFW.glfwGetKey(window, GLFW.GLFW_KEY_SPACE) == GLFW.GLFW_PRESS)
                    remotePlayer.jump();
                remotePlayer.update(deltaTime);
                remotePlayer.advance(forwardSpeed * deltaTime);
            }
        } else {
            if (GLFW.glfwGetKey(window, GLFW.GLFW_KEY_LEFT) == GLFW.GLFW_PRESS)
                localPlayer.moveLeft(deltaTime);
            if (GLFW.glfwGetKey(window, GLFW.GLFW_KEY_RIGHT) == GLFW.GLFW_PRESS)
                localPlayer.moveRight(deltaTime);
            if (GLFW.glfwGetKey(window, GLFW.GLFW_KEY_SPACE) == GLFW.GLFW_PRESS)
                localPlayer.jump();
            localPlayer.update(deltaTime);
            localPlayer.advance(forwardSpeed * deltaTime);
        }
    }

    // ----- Obstacle Spawning -----
    private void spawnObstacles() {
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastObstacleTime > 1500) {
            float spawnDistance = 50f;
            float zPos = localPlayer.getZ() + spawnDistance;
            float[] lanes = { -2f, 0f, 2f };
            float xPos = lanes[random.nextInt(lanes.length)];
            obstacles.add(new Obstacle(xPos, 0, zPos, obstacleModel));
            lastObstacleTime = currentTime;
        }
    }
    private void spawnLocalObstacles() {
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastLocalObstacleTime > 2000) {
            float spawnDistance = 50f;
            float zPos = localPlayer.getZ() + spawnDistance;
            float[] lanes = { -2f, 0f, 2f };
            float xPos = lanes[random.nextInt(lanes.length)];
            localObstacles.add(new Obstacle(xPos, 0, zPos, obstacleModel));
            lastLocalObstacleTime = currentTime;
        }
    }
    private void spawnRemoteObstacles() {
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastRemoteObstacleTime > 2000) {
            float spawnDistance = 50f;
            float zPos = remotePlayer.getZ() + spawnDistance;
            float[] lanes = { -2f, 0f, 2f };
            float xPos = lanes[random.nextInt(lanes.length)];
            remoteObstacles.add(new Obstacle(xPos, 0, zPos, obstacleModel));
            lastRemoteObstacleTime = currentTime;
        }
    }

    // ----- Collision Detection -----
    private void checkLocalCollisions() {
        List<Obstacle> list = (isMultiplayer) ? localObstacles : obstacles;
        for (Obstacle obs : list) {
            if (localPlayer.collidesWith(obs)) {
                localPlayer.loseLife();
                playSoundEffect("death.wav");
                if (localPlayer.getLives() <= 0)
                    localGameOver = true;
                else {
                    resetPlayerAfterCollision(localPlayer);
                    list.clear();
                }
                break;
            }
        }
    }
    private void checkRemoteCollisions() {
        for (Obstacle obs : remoteObstacles) {
            if (remotePlayer.collidesWith(obs)) {
                remotePlayer.loseLife();
                playSoundEffect("death.wav");
                if (remotePlayer.getLives() <= 0)
                    remoteGameOver = true;
                else {
                    remotePlayer.setPosition(0, 0, remotePlayer.getZ());
                    remoteObstacles.clear();
                }
                break;
            }
        }
    }
    private void resetPlayerAfterCollision(Player player) {
        player.setPosition(0, 0, player.getZ());
    }
    private void updateScore() {
        if (!localGameOver)
            localScore++;
        if (!remoteGameOver)
            remoteScore++;
    }

    // ----- Camera and Projection Utilities -----
    private void updateCameraForPlayer(Player player) {
        float cameraDistance = 10.0f;
        float cameraHeight = 5.0f;
        float targetCameraX = player.getX();
        float targetCameraZ = player.getZ() - cameraDistance;
        float targetCameraY = player.getY() + cameraHeight;
        GL11.glLoadIdentity();
        gluLookAt(targetCameraX, targetCameraY, targetCameraZ,
                player.getX(), player.getY(), player.getZ(),
                0.0f, 1.0f, 0.0f);
    }
    private void setPerspectiveProjection(float fov, float aspect, float zNear, float zFar) {
        float ymax = (float)(zNear * Math.tan(Math.toRadians(fov / 2.0)));
        float xmax = ymax * aspect;
        GL11.glMatrixMode(GL11.GL_PROJECTION);
        GL11.glLoadIdentity();
        GL11.glFrustum(-xmax, xmax, -ymax, ymax, zNear, zFar);
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
    }
    private void gluLookAt(float eyeX, float eyeY, float eyeZ,
                           float centerX, float centerY, float centerZ,
                           float upX, float upY, float upZ) {
        float[] forward = { centerX - eyeX, centerY - eyeY, centerZ - eyeZ };
        normalize(forward);
        float[] up = { upX, upY, upZ };
        float[] side = crossProduct(forward, up);
        normalize(side);
        up = crossProduct(side, forward);
        FloatBuffer viewMatrix = BufferUtils.createFloatBuffer(16);
        viewMatrix.put(new float[]{
                side[0],   up[0],   -forward[0], 0,
                side[1],   up[1],   -forward[1], 0,
                side[2],   up[2],   -forward[2], 0,
                -dotProduct(side, new float[]{ eyeX, eyeY, eyeZ }),
                -dotProduct(up, new float[]{ eyeX, eyeY, eyeZ }),
                dotProduct(forward, new float[]{ eyeX, eyeY, eyeZ }),
                1
        });
        viewMatrix.flip();
        GL11.glMultMatrixf(viewMatrix);
    }
    private void normalize(float[] v) {
        float length = (float)Math.sqrt(v[0]*v[0] + v[1]*v[1] + v[2]*v[2]);
        if (length != 0) {
            v[0] /= length;
            v[1] /= length;
            v[2] /= length;
        }
    }
    private float[] crossProduct(float[] a, float[] b) {
        return new float[]{
                a[1]*b[2] - a[2]*b[1],
                a[2]*b[0] - a[0]*b[2],
                a[0]*b[1] - a[1]*b[0]
        };
    }
    private float dotProduct(float[] a, float[] b) {
        return a[0]*b[0] + a[1]*b[1] + a[2]*b[2];
    }
    private void initLighting() {
        GL11.glEnable(GL11.GL_LIGHTING);
        GL11.glEnable(GL11.GL_LIGHT0);
        GL11.glEnable(GL11.GL_DEPTH_TEST);
        GL11.glDepthFunc(GL11.GL_LEQUAL);
        FloatBuffer lightPos = BufferUtils.createFloatBuffer(4);
        lightPos.put(new float[]{0.0f, 10.0f, 10.0f, 1.0f});
        lightPos.flip();
        GL11.glLightfv(GL11.GL_LIGHT0, GL11.GL_POSITION, lightPos);
        FloatBuffer ambientLight = BufferUtils.createFloatBuffer(4);
        ambientLight.put(new float[]{0.4f, 0.4f, 0.4f, 1.0f});
        ambientLight.flip();
        GL11.glLightfv(GL11.GL_LIGHT0, GL11.GL_AMBIENT, ambientLight);
        FloatBuffer diffuseLight = BufferUtils.createFloatBuffer(4);
        diffuseLight.put(new float[]{1.0f, 1.0f, 1.0f, 1.0f});
        diffuseLight.flip();
        GL11.glLightfv(GL11.GL_LIGHT0, GL11.GL_DIFFUSE, diffuseLight);
        FloatBuffer specularLight = BufferUtils.createFloatBuffer(4);
        specularLight.put(new float[]{1.0f, 1.0f, 1.0f, 1.0f});
        specularLight.flip();
        GL11.glLightfv(GL11.GL_LIGHT0, GL11.GL_SPECULAR, specularLight);
        GL11.glEnable(GL11.GL_COLOR_MATERIAL);
        GL11.glColorMaterial(GL11.GL_FRONT_AND_BACK, GL11.GL_AMBIENT_AND_DIFFUSE);
    }

    // ----- HUD Rendering -----
    private void renderHUDForPlayer(Player p, int hudOffsetX, int score) {
        GL11.glMatrixMode(GL11.GL_PROJECTION);
        GL11.glPushMatrix();
        GL11.glLoadIdentity();
        int hudWidth = (isMultiplayer && isHost) ? width/2 : width;
        GL11.glOrtho(0, hudWidth, height, 0, -1, 1);
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        GL11.glPushMatrix();
        GL11.glLoadIdentity();

        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        // Draw a semi-transparent background for the HUD.
        GL11.glColor4f(0, 0, 0, 0.5f);
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glVertex2f(10 + hudOffsetX, 10);
        GL11.glVertex2f(250 + hudOffsetX, 10);
        GL11.glVertex2f(250 + hudOffsetX, 50);
        GL11.glVertex2f(10 + hudOffsetX, 50);
        GL11.glEnd();

        // Draw score text.
        GL11.glColor3f(1, 1, 1);
        drawText2D(60 + hudOffsetX, 30, "Score: " + score);
        // Draw heart icons representing lives.
        for (int i = 0; i < p.getLives(); i++) {
            drawHeart(10 + hudOffsetX + i * 20, 10, 15);
        }

        GL11.glDisable(GL11.GL_BLEND);
        GL11.glPopMatrix();
        GL11.glMatrixMode(GL11.GL_PROJECTION);
        GL11.glPopMatrix();
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
    }

    // ----- Helper: Draw Text using STBEasyFont -----
    private void drawText2D(float x, float y, String text) {
        ByteBuffer charBuffer = BufferUtils.createByteBuffer(99999);
        int numQuads = STBEasyFont.stb_easy_font_print(x, y, text, null, charBuffer);
        GL11.glEnableClientState(GL11.GL_VERTEX_ARRAY);
        GL11.glVertexPointer(2, GL11.GL_FLOAT, 16, charBuffer);
        GL11.glDrawArrays(GL11.GL_QUADS, 0, numQuads * 4);
        GL11.glDisableClientState(GL11.GL_VERTEX_ARRAY);
    }

    // ----- Helper: Draw a Heart for the HUD -----
    private void drawHeart(int x, int y, int size) {
        GL11.glPushMatrix();
        GL11.glTranslatef(x, y, 0);
        GL11.glScalef(size / 20.0f, size / 20.0f, 1.0f);
        GL11.glColor3f(1.0f, 0.0f, 0.0f);
        GL11.glBegin(GL11.GL_POLYGON);
        for (int angle = 0; angle < 360; angle += 10) {
            double rad = Math.toRadians(angle);
            double xCoord = 16 * Math.pow(Math.sin(rad), 3);
            double yCoord = 13 * Math.cos(rad) - 5 * Math.cos(2 * rad) - 2 * Math.cos(3 * rad) - Math.cos(4 * rad);
            GL11.glVertex2d(xCoord, -yCoord);
        }
        GL11.glEnd();
        GL11.glPopMatrix();
    }

    // ----- Game-Over Screen -----
    private void renderGameOver(String message) {
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glMatrixMode(GL11.GL_PROJECTION);
        GL11.glPushMatrix();
        GL11.glLoadIdentity();
        GL11.glOrtho(0, width, height, 0, -1, 1);
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        GL11.glPushMatrix();
        GL11.glLoadIdentity();

        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glColor4f(0, 0, 0, 0.7f);
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glVertex2f(0, 0);
        GL11.glVertex2f(width, 0);
        GL11.glVertex2f(width, height);
        GL11.glVertex2f(0, height);
        GL11.glEnd();

        GL11.glColor3f(1, 1, 1);
        drawText2D(width / 2 - 140, height / 2 - 40, message);

        float restartX = width / 2 - 150;
        float restartY = height / 2 + 20;
        float restartW = 120, restartH = 40;
        float exitX = width / 2 + 30;
        float exitY = height / 2 + 20;
        float exitW = 120, exitH = 40;

        // Draw Restart button.
        GL11.glColor3f(0, 0, 1);
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glVertex2f(restartX, restartY);
        GL11.glVertex2f(restartX + restartW, restartY);
        GL11.glVertex2f(restartX + restartW, restartY + restartH);
        GL11.glVertex2f(restartX, restartY + restartH);
        GL11.glEnd();
        GL11.glColor3f(1, 1, 1);
        GL11.glBegin(GL11.GL_LINE_LOOP);
        GL11.glVertex2f(restartX, restartY);
        GL11.glVertex2f(restartX + restartW, restartY);
        GL11.glVertex2f(restartX + restartW, restartY + restartH);
        GL11.glVertex2f(restartX, restartY + restartH);
        GL11.glEnd();
        drawText2D(restartX + 20, restartY + 25, "Restart");

        // Draw Exit button.
        GL11.glColor3f(0, 0, 1);
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glVertex2f(exitX, exitY);
        GL11.glVertex2f(exitX + exitW, exitY);
        GL11.glVertex2f(exitX + exitW, exitY + exitH);
        GL11.glVertex2f(exitX, exitY + exitH);
        GL11.glEnd();
        GL11.glColor3f(1, 1, 1);
        GL11.glBegin(GL11.GL_LINE_LOOP);
        GL11.glVertex2f(exitX, exitY);
        GL11.glVertex2f(exitX + exitW, exitY);
        GL11.glVertex2f(exitX + exitW, exitY + exitH);
        GL11.glVertex2f(exitX, exitY + exitH);
        GL11.glEnd();
        drawText2D(exitX + 30, exitY + 25, "Exit");

        if (GLFW.glfwGetMouseButton(window, GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS && !gameOverButtonClicked) {
            double[] mouseX = new double[1], mouseY = new double[1];
            GLFW.glfwGetCursorPos(window, mouseX, mouseY);
            if (mouseX[0] >= restartX && mouseX[0] <= restartX + restartW &&
                    mouseY[0] >= restartY && mouseY[0] <= restartY + restartH) {
                restartGame();
            } else if (mouseX[0] >= exitX && mouseX[0] <= exitX + exitW &&
                    mouseY[0] >= exitY && mouseY[0] <= exitY + exitH) {
                System.exit(0);
            }
            gameOverButtonClicked = true;
        } else if (GLFW.glfwGetMouseButton(window, GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_RELEASE) {
            gameOverButtonClicked = false;
        }

        GL11.glDisable(GL11.GL_BLEND);
        GL11.glPopMatrix();
        GL11.glMatrixMode(GL11.GL_PROJECTION);
        GL11.glPopMatrix();
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        GL11.glEnable(GL11.GL_LIGHTING);
    }

    // ----- Restart Game: Reset state after game over -----
    private void restartGame() {
        if (isMultiplayer) {
            localPlayer.setPosition(0, 0, 10.0f);
            localPlayer.lives = 3;
            remotePlayer.setPosition(0, 0, 10.0f);
            remotePlayer.lives = 3;
            localScore = 0;
            remoteScore = 0;
            localGameOver = false;
            remoteGameOver = false;
            localObstacles.clear();
            remoteObstacles.clear();
        } else {
            localPlayer.setPosition(0, 0, 10.0f);
            localPlayer.lives = 3;
            localScore = 0;
            localGameOver = false;
            obstacles.clear();
        }
        gameStarted = false;  // Return to the start screen
    }

    // ----- OBJLoader, Model, and ModelRenderer Classes -----
    static class OBJLoader {
        public static Model loadModel(String fileName) throws IOException {
            BufferedReader reader = new BufferedReader(new FileReader(fileName));
            String line;
            List<float[]> vertices = new ArrayList<>();
            List<float[]> normals = new ArrayList<>();
            List<int[]> faces = new ArrayList<>();
            while ((line = reader.readLine()) != null) {
                String[] tokens = line.trim().split("\\s+");
                if (tokens.length == 0) continue;
                if (tokens[0].equals("v")) {
                    float[] vertex = {
                            Float.parseFloat(tokens[1]),
                            Float.parseFloat(tokens[2]),
                            Float.parseFloat(tokens[3])
                    };
                    vertices.add(vertex);
                } else if (tokens[0].equals("vn")) {
                    float[] normal = {
                            Float.parseFloat(tokens[1]),
                            Float.parseFloat(tokens[2]),
                            Float.parseFloat(tokens[3])
                    };
                    normals.add(normal);
                } else if (tokens[0].equals("f")) {
                    int[] face = new int[3];
                    for (int i = 1; i <= 3; i++) {
                        String[] parts = tokens[i].split("/");
                        face[i - 1] = Integer.parseInt(parts[0]) - 1;
                    }
                    faces.add(face);
                }
            }
            reader.close();
            float[] verticesArray = new float[vertices.size() * 3];
            float[] normalsArray = new float[vertices.size() * 3];
            int[] indicesArray = new int[faces.size() * 3];
            int vi = 0;
            for (float[] v : vertices) {
                verticesArray[vi++] = v[0];
                verticesArray[vi++] = v[1];
                verticesArray[vi++] = v[2];
            }
            int ni = 0;
            for (float[] n : normals) {
                normalsArray[ni++] = n[0];
                normalsArray[ni++] = n[1];
                normalsArray[ni++] = n[2];
            }
            int fi = 0;
            for (int[] face : faces) {
                indicesArray[fi++] = face[0];
                indicesArray[fi++] = face[1];
                indicesArray[fi++] = face[2];
            }
            return new Model(verticesArray, normalsArray, indicesArray);
        }
    }
    static class Model {
        private float[] vertices;
        private float[] normals;
        private int[] indices;
        public Model(float[] vertices, float[] normals, int[] indices) {
            this.vertices = vertices;
            this.normals = normals;
            this.indices = indices;
        }
        public float[] getVertices() { return vertices; }
        public float[] getNormals() { return normals; }
        public int[] getIndices() { return indices; }
    }
    static class ModelRenderer {
        public static void renderModel(Model model) {
            if (model == null) return;
            float[] vertices = model.getVertices();
            float[] normals = model.getNormals();
            int[] indices = model.getIndices();
            GL11.glBegin(GL11.GL_TRIANGLES);
            for (int idx : indices) {
                int vi = idx * 3;
                GL11.glNormal3f(normals[vi], normals[vi+1], normals[vi+2]);
                GL11.glVertex3f(vertices[vi], vertices[vi+1], vertices[vi+2]);
            }
            GL11.glEnd();
        }
    }

    // ----- Terrain Class -----
    static class Terrain {
        private float[] vertices;
        private float[] normals;
        private int[] indices;
        private final int segments = 100;
        private final float trackWidth = 6.0f;
        private final float trackLength = 100.0f;
        private float currentBaseZ;

        public void update(float baseZ) {
            currentBaseZ = baseZ;
            vertices = new float[segments * 2 * 3];
            normals = new float[segments * 2 * 3];
            indices = new int[(segments - 1) * 6];
            for (int i = 0; i < segments; i++) {
                float z = baseZ + (trackLength * i / (segments - 1));
                vertices[i * 6] = -trackWidth / 2;
                vertices[i * 6 + 1] = 0;
                vertices[i * 6 + 2] = z;
                vertices[i * 6 + 3] = trackWidth / 2;
                vertices[i * 6 + 4] = 0;
                vertices[i * 6 + 5] = z;
                normals[i * 6] = 0;     normals[i * 6 + 1] = 1; normals[i * 6 + 2] = 0;
                normals[i * 6 + 3] = 0;   normals[i * 6 + 4] = 1; normals[i * 6 + 5] = 0;
            }
            for (int i = 0; i < segments - 1; i++) {
                indices[i * 6]     = i * 2;
                indices[i * 6 + 1] = i * 2 + 1;
                indices[i * 6 + 2] = i * 2 + 2;
                indices[i * 6 + 3] = i * 2 + 1;
                indices[i * 6 + 4] = i * 2 + 3;
                indices[i * 6 + 5] = i * 2 + 2;
            }
        }
        public void render() {
            GL11.glColor3f(0.2f, 0.6f, 0.3f);
            GL11.glBegin(GL11.GL_TRIANGLES);
            for (int i = 0; i < indices.length; i++) {
                int vi = indices[i] * 3;
                int ni = indices[i] * 3;
                GL11.glNormal3f(normals[ni], normals[ni+1], normals[ni+2]);
                GL11.glVertex3f(vertices[vi], vertices[vi+1], vertices[vi+2]);
            }
            GL11.glEnd();
            // Draw lane markers.
            GL11.glColor3f(1.0f, 1.0f, 1.0f);
            GL11.glBegin(GL11.GL_LINES);
            for (float z = currentBaseZ; z < currentBaseZ + trackLength; z += 2.0f) {
                GL11.glVertex3f(-trackWidth / 2, 0.01f, z);
                GL11.glVertex3f(-trackWidth / 2, 0.01f, z + 1.0f);
                GL11.glVertex3f(trackWidth / 2, 0.01f, z);
                GL11.glVertex3f(trackWidth / 2, 0.01f, z + 1.0f);
            }
            GL11.glEnd();
        }
        public float getTerrainHeightAt(float x, float z) { return 0.0f; }
    }

    // ----- Player Classes -----
    static class Player {
        protected float x, y, z;
        protected float jumpVelocity = 0;
        protected boolean isJumping = false;
        protected float gravity = -9.8f;
        protected float laneWidth = 2.0f;
        protected float moveSpeed = 5.0f;
        protected int lives = 3;
        // Default color blue.
        protected float r = 0.0f, g = 0.0f, b = 1.0f;

        public Player(float x, float y, float z) { this.x = x; this.y = y; this.z = z; }
        public void setColor(float r, float g, float b) { this.r = r; this.g = g; this.b = b; }
        public float getX() { return x; }
        public float getY() { return y; }
        public float getZ() { return z; }
        public int getLives() { return lives; }
        public void loseLife() { lives--; }
        public void moveLeft(float deltaTime) {
            x += moveSpeed * deltaTime;
            x = Math.max(x, -laneWidth);
        }
        public void moveRight(float deltaTime) {
            x -= moveSpeed * deltaTime;
            x = Math.min(x, laneWidth);
        }
        public void jump() {
            if (!isJumping) {
                jumpVelocity = 5.0f;
                isJumping = true;
            }
        }
        public void update(float deltaTime) {
            if (isJumping) {
                y += jumpVelocity * deltaTime;
                jumpVelocity += gravity * deltaTime;
                if (y <= 0) { y = 0; isJumping = false; jumpVelocity = 0; }
            }
        }
        public void advance(float dz) { z += dz; }
        public void render() {
            float playerHeight = 1.8f;
            GL11.glPushMatrix();
            GL11.glTranslatef(x, y + playerHeight / 2, z);
            GL11.glColor3f(r, g, b);
            // Head
            GL11.glPushMatrix();
            GL11.glTranslatef(0, 0.3f, 0);
            GL11.glScalef(0.3f, 0.3f, 0.3f);
            renderSphere();
            GL11.glPopMatrix();
            // Body
            GL11.glPushMatrix();
            GL11.glScalef(0.4f, 0.6f, 0.2f);
            renderCube();
            GL11.glPopMatrix();
            // Arms
            GL11.glPushMatrix();
            GL11.glTranslatef(0.3f, 0, 0);
            GL11.glScalef(0.2f, 0.5f, 0.1f);
            renderCube();
            GL11.glPopMatrix();
            GL11.glPushMatrix();
            GL11.glTranslatef(-0.3f, 0, 0);
            GL11.glScalef(0.2f, 0.5f, 0.1f);
            renderCube();
            GL11.glPopMatrix();
            // Legs
            GL11.glPushMatrix();
            GL11.glTranslatef(0.15f, -0.5f, 0);
            GL11.glScalef(0.2f, 0.5f, 0.1f);
            renderCube();
            GL11.glPopMatrix();
            GL11.glPushMatrix();
            GL11.glTranslatef(-0.15f, -0.5f, 0);
            GL11.glScalef(0.2f, 0.5f, 0.1f);
            renderCube();
            GL11.glPopMatrix();
            GL11.glPopMatrix();
        }
        private void renderCube() {
            GL11.glBegin(GL11.GL_QUADS);
            // Front face
            GL11.glNormal3f(0, 0, 1);
            GL11.glVertex3f(-0.5f,-0.5f,0.5f);
            GL11.glVertex3f(0.5f,-0.5f,0.5f);
            GL11.glVertex3f(0.5f,0.5f,0.5f);
            GL11.glVertex3f(-0.5f,0.5f,0.5f);
            // Back face
            GL11.glNormal3f(0, 0, -1);
            GL11.glVertex3f(-0.5f,-0.5f,-0.5f);
            GL11.glVertex3f(0.5f,-0.5f,-0.5f);
            GL11.glVertex3f(0.5f,0.5f,-0.5f);
            GL11.glVertex3f(-0.5f,0.5f,-0.5f);
            // Left face
            GL11.glNormal3f(-1, 0, 0);
            GL11.glVertex3f(-0.5f,-0.5f,-0.5f);
            GL11.glVertex3f(-0.5f,-0.5f,0.5f);
            GL11.glVertex3f(-0.5f,0.5f,0.5f);
            GL11.glVertex3f(-0.5f,0.5f,-0.5f);
            // Right face
            GL11.glNormal3f(1, 0, 0);
            GL11.glVertex3f(0.5f,-0.5f,-0.5f);
            GL11.glVertex3f(0.5f,-0.5f,0.5f);
            GL11.glVertex3f(0.5f,0.5f,0.5f);
            GL11.glVertex3f(0.5f,0.5f,-0.5f);
            // Top face
            GL11.glNormal3f(0, 1, 0);
            GL11.glVertex3f(-0.5f,0.5f,-0.5f);
            GL11.glVertex3f(0.5f,0.5f,-0.5f);
            GL11.glVertex3f(0.5f,0.5f,0.5f);
            GL11.glVertex3f(-0.5f,0.5f,0.5f);
            // Bottom face
            GL11.glNormal3f(0, -1, 0);
            GL11.glVertex3f(-0.5f,-0.5f,-0.5f);
            GL11.glVertex3f(0.5f,-0.5f,-0.5f);
            GL11.glVertex3f(0.5f,-0.5f,0.5f);
            GL11.glVertex3f(-0.5f,-0.5f,0.5f);
            GL11.glEnd();
        }
        private void renderSphere() {
            int slices = 16, stacks = 16;
            for (int i = 0; i < stacks; i++) {
                double lat0 = Math.PI * (-0.5 + (double)(i - 1) / stacks);
                double z0 = Math.sin(lat0);
                double zr0 = Math.cos(lat0);
                double lat1 = Math.PI * (-0.5 + (double)i / stacks);
                double z1 = Math.sin(lat1);
                double zr1 = Math.cos(lat1);
                GL11.glBegin(GL11.GL_QUAD_STRIP);
                for (int j = 0; j <= slices; j++) {
                    double lng = 2 * Math.PI * (j - 1) / slices;
                    double x = Math.cos(lng);
                    double y = Math.sin(lng);
                    GL11.glNormal3d(x * zr0, y * zr0, z0);
                    GL11.glVertex3d(x * zr0, y * zr0, z0);
                    GL11.glNormal3d(x * zr1, y * zr1, z1);
                    GL11.glVertex3d(x * zr1, y * zr1, z1);
                }
                GL11.glEnd();
            }
        }
        public boolean collidesWith(Obstacle obs) {
            float playerWidth = 0.7f;
            float playerDepth = 0.7f;
            return Math.abs(x - obs.getX()) < (playerWidth + obs.getWidth()) / 2 &&
                    Math.abs(z - obs.getZ()) < (playerDepth + obs.getDepth()) / 2;
        }
        public void setPosition(float x, float y, float z) { this.x = x; this.y = y; this.z = z; }
    }

    static class RemotePlayer extends Player {
        public RemotePlayer(float x, float y, float z) {
            super(x, y, z);
            // Default remote player color is set to red.
            setColor(1.0f, 0.0f, 0.0f);
        }
        public void setPosition(float x, float y, float z) { this.x = x; this.y = y; this.z = z; }
    }

    // ----- Obstacle Class -----
    static class Obstacle {
        private float x, y, z;
        private float width = 0.8f;
        private float height = 1.0f;
        private float depth = 0.8f;
        private Model model;
        public Obstacle(float x, float y, float z) { this.x = x; this.y = y; this.z = z; }
        public Obstacle(float x, float y, float z, Model model) { this(x, y, z); this.model = model; }
        public float getX() { return x; }
        public float getY() { return y; }
        public float getZ() { return z; }
        public float getWidth() { return width; }
        public float getDepth() { return depth; }
        public void render() {
            GL11.glPushMatrix();
            GL11.glTranslatef(x, y, z);
            if (model != null)
                ModelRenderer.renderModel(model);
            else
                renderFallbackCube();
            GL11.glPopMatrix();
        }
        private void renderFallbackCube() {
            GL11.glColor3f(1.0f, 0.0f, 0.0f);
            GL11.glBegin(GL11.GL_QUADS);
            // Front face
            GL11.glNormal3f(0, 0, 1);
            GL11.glVertex3f(-0.5f, -0.5f, 0.5f);
            GL11.glVertex3f(0.5f, -0.5f, 0.5f);
            GL11.glVertex3f(0.5f, 0.5f, 0.5f);
            GL11.glVertex3f(-0.5f, 0.5f, 0.5f);
            // Back face
            GL11.glNormal3f(0, 0, -1);
            GL11.glVertex3f(-0.5f, -0.5f, -0.5f);
            GL11.glVertex3f(0.5f, -0.5f, -0.5f);
            GL11.glVertex3f(0.5f, 0.5f, -0.5f);
            GL11.glVertex3f(-0.5f, 0.5f, -0.5f);
            // Left face
            GL11.glNormal3f(-1, 0, 0);
            GL11.glVertex3f(-0.5f, -0.5f, -0.5f);
            GL11.glVertex3f(-0.5f, -0.5f, 0.5f);
            GL11.glVertex3f(-0.5f, 0.5f, 0.5f);
            GL11.glVertex3f(-0.5f, 0.5f, -0.5f);
            // Right face
            GL11.glNormal3f(1, 0, 0);
            GL11.glVertex3f(0.5f, -0.5f, -0.5f);
            GL11.glVertex3f(0.5f, -0.5f, 0.5f);
            GL11.glVertex3f(0.5f, 0.5f, 0.5f);
            GL11.glVertex3f(0.5f, 0.5f, -0.5f);
            // Top face
            GL11.glNormal3f(0, 1, 0);
            GL11.glVertex3f(-0.5f, 0.5f, -0.5f);
            GL11.glVertex3f(0.5f, 0.5f, -0.5f);
            GL11.glVertex3f(0.5f, 0.5f, 0.5f);
            GL11.glVertex3f(-0.5f, 0.5f, 0.5f);
            // Bottom face
            GL11.glNormal3f(0, -1, 0);
            GL11.glVertex3f(-0.5f, -0.5f, -0.5f);
            GL11.glVertex3f(0.5f, -0.5f, -0.5f);
            GL11.glVertex3f(0.5f, -0.5f, 0.5f);
            GL11.glVertex3f(-0.5f, -0.5f, 0.5f);
            GL11.glEnd();
        }
    }

    // ----- NetworkManager Class -----
    class NetworkManager implements Runnable {
        private Socket socket;
        private BufferedReader in;
        private PrintWriter out;
        private volatile boolean running = true;
        private RemotePlayer remotePlayer;

        public NetworkManager(String serverAddress, int port, RemotePlayer remotePlayer) throws IOException {
            this.socket = new Socket(serverAddress, port);
            this.remotePlayer = remotePlayer;
            in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            out = new PrintWriter(socket.getOutputStream(), true);
            new Thread(this).start();
        }

        @Override
        public void run() {
            try {
                String message;
                while (running && (message = in.readLine()) != null) {
                    processMessage(message);
                }
            } catch (IOException e) {
                System.err.println("Network error: " + e.getMessage());
            }
        }

        private void processMessage(String message) {
            try {
                String[] parts = message.split(",");
                if (parts.length == 3) {
                    float x = Float.parseFloat(parts[0]);
                    float y = Float.parseFloat(parts[1]);
                    float z = Float.parseFloat(parts[2]);
                    remotePlayer.setPosition(x, y, z);
                }
            } catch (Exception e) {
                System.err.println("Error processing message: " + message);
            }
        }

        public void send(String message) {
            out.println(message);
        }

        public void stop() {
            running = false;
            try {
                socket.close();
            } catch (IOException e) { }
        }
    }
}
