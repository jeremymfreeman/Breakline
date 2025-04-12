// javac -classpath ".;C:\Program Files\lwjgl-release-3.3.4-custom\*" RunnerGame.java
// java -classpath ".;C:\Program Files\lwjgl-release-3.3.4-custom\*" RunnerGame

import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.lwjgl.opengl.GL11;

import java.nio.FloatBuffer;
import org.lwjgl.BufferUtils;

public class RunnerGame {
    private long window;
    private int width = 800;
    private int height = 600;
    private Player player;
    private Terrain terrain;
    private List<Obstacle> obstacles = new ArrayList<>();
    private Random random = new Random();
    private float gameSpeed = 0.05f;
    private int score = 0;
    private long lastObstacleTime = 0;
    private boolean gameOver = false;

    public static void main(String[] args) {
        new RunnerGame().run();
    }

    public void run() {
        init();
        loop();
        GLFW.glfwDestroyWindow(window);
        GLFW.glfwTerminate();
    }

    private void init() {
        if (!GLFW.glfwInit()) {
            throw new IllegalArgumentException("Unable to initialize GLFW");
        }

        window = GLFW.glfwCreateWindow(width, height, "Subway Runner", 0, 0);
        if (window == 0) {
            throw new RuntimeException("Failed to create the GLFW window");
        }

        GLFW.glfwMakeContextCurrent(window);
        GL.createCapabilities();

        GL11.glMatrixMode(GL11.GL_PROJECTION);
        GL11.glLoadIdentity();
        setPerspectiveProjection(45.0f, (float) width / height, 0.1f, 100.0f);
        GL11.glMatrixMode(GL11.GL_MODELVIEW);

        initLighting();

        GL11.glEnable(GL11.GL_COLOR_MATERIAL);
        GL11.glColorMaterial(GL11.GL_FRONT_AND_BACK, GL11.GL_AMBIENT_AND_DIFFUSE);

        FloatBuffer lightPosition = BufferUtils.createFloatBuffer(4).put(new float[]{0.0f, 10.0f, 10.0f, 1.0f});
        lightPosition.flip();
        GL11.glLightfv(GL11.GL_LIGHT0, GL11.GL_POSITION, lightPosition);

        GL11.glEnable(GL11.GL_DEPTH_TEST);
        GL11.glDepthFunc(GL11.GL_LEQUAL);

        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);

        player = new Player(0, 0, 0);
        terrain = new Terrain();
    }

    private void loop() {
        long lastTime = System.currentTimeMillis();

        while (!GLFW.glfwWindowShouldClose(window)) {
            long currentTime = System.currentTimeMillis();
            float deltaTime = (currentTime - lastTime) / 1000.0f;
            lastTime = currentTime;

            GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
            GL11.glLoadIdentity();

            if (!gameOver) {
                updatePlayerMovement(deltaTime);
                updateCamera();
                spawnObstacles();
                updateObstacles();
                checkCollisions();
                updateScore();
            } else {
                renderGameOver();
            }

            terrain.render();
            player.render(terrain);
            for (Obstacle obstacle : obstacles) {
                obstacle.render();
            }

            GLFW.glfwSwapBuffers(window);
            GLFW.glfwPollEvents();
        }
    }

    private void updatePlayerMovement(float deltaTime) {
        if (GLFW.glfwGetKey(window, GLFW.GLFW_KEY_LEFT) == GLFW.GLFW_PRESS) {
            player.moveLeft(deltaTime);
        }
        if (GLFW.glfwGetKey(window, GLFW.GLFW_KEY_RIGHT) == GLFW.GLFW_PRESS) {
            player.moveRight(deltaTime);
        }
        if (GLFW.glfwGetKey(window, GLFW.GLFW_KEY_UP) == GLFW.GLFW_PRESS) {
            player.jump();
        }

        player.update(deltaTime);
    }

    private void updateCamera() {
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

    private void spawnObstacles() {
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastObstacleTime > 1500) {
            // Spawn FAR ahead of player (large positive Z)
            float spawnDistance = 50f;
            float zPos = player.getZ() + spawnDistance;

            float[] lanes = {-2f, 0f, 2f};
            float xPos = lanes[random.nextInt(lanes.length)];

            obstacles.add(new Obstacle(xPos, 0, zPos));
            System.out.println("Spawned obstacle at: X=" + xPos + " Z=" + zPos +
                    " | Player at: Z=" + player.getZ());
            lastObstacleTime = currentTime;
        }
    }

    private void updateObstacles() {
        for (int i = obstacles.size() - 1; i >= 0; i--) {
            Obstacle o = obstacles.get(i);
            o.update(gameSpeed);

            // Remove when behind player (z < player.z - threshold)
            if (o.getZ() < player.getZ() - 10f) {
                obstacles.remove(i);
                score++;
            }
        }
    }
    private void checkCollisions() {
        for (Obstacle obstacle : obstacles) {
            if (player.collidesWith(obstacle)) {
                gameOver = true;
                break;
            }
        }
    }

    private void updateScore() {
        score += 1;
    }

    private void renderGameOver() {
        // Simple game over text rendering using immediate mode
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glColor3f(1.0f, 0.0f, 0.0f);

        // Switch to orthographic projection for 2D text
        GL11.glMatrixMode(GL11.GL_PROJECTION);
        GL11.glPushMatrix();
        GL11.glLoadIdentity();
        GL11.glOrtho(0, width, height, 0, -1, 1);

        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        GL11.glPushMatrix();
        GL11.glLoadIdentity();

        // Draw a rectangle as background
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glVertex2f(width/2 - 100, height/2 - 20);
        GL11.glVertex2f(width/2 + 100, height/2 - 20);
        GL11.glVertex2f(width/2 + 100, height/2 + 20);
        GL11.glVertex2f(width/2 - 100, height/2 + 20);
        GL11.glEnd();

        // Draw text using simple geometry (just for demonstration)
        GL11.glColor3f(1.0f, 1.0f, 1.0f);
        drawText(width/2 - 90, height/2, "GAME OVER! Score: " + score);

        GL11.glPopMatrix();
        GL11.glMatrixMode(GL11.GL_PROJECTION);
        GL11.glPopMatrix();
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        GL11.glEnable(GL11.GL_LIGHTING);
    }

    private void drawText(float x, float y, String text) {
        // Very simple text rendering using lines
        // This is just a placeholder - in a real game you'd want a proper text rendering solution
        GL11.glPushMatrix();
        GL11.glTranslatef(x, y, 0);
        GL11.glScalef(0.1f, 0.1f, 0.1f);

        for (char c : text.toCharArray()) {
            // Just draw a line for each character (very basic)
            GL11.glBegin(GL11.GL_LINES);
            GL11.glVertex2f(0, 0);
            GL11.glVertex2f(5, 5);
            GL11.glEnd();
            GL11.glTranslatef(5, 0, 0);
        }

        GL11.glPopMatrix();
    }

    private void initLighting() {
        GL11.glEnable(GL11.GL_LIGHTING);
        GL11.glEnable(GL11.GL_LIGHT0);
        GL11.glEnable(GL11.GL_DEPTH_TEST);
        GL11.glDepthFunc(GL11.GL_LEQUAL);

        FloatBuffer lightPosition = BufferUtils.createFloatBuffer(4).put(new float[]{0.0f, 10.0f, 10.0f, 1.0f});
        lightPosition.flip();
        GL11.glLightfv(GL11.GL_LIGHT0, GL11.GL_POSITION, lightPosition);

        FloatBuffer ambientLight = BufferUtils.createFloatBuffer(4).put(new float[]{0.4f, 0.4f, 0.4f, 1.0f});
        ambientLight.flip();
        GL11.glLightfv(GL11.GL_LIGHT0, GL11.GL_AMBIENT, ambientLight);

        FloatBuffer diffuseLight = BufferUtils.createFloatBuffer(4).put(new float[]{1.0f, 1.0f, 1.0f, 1.0f});
        diffuseLight.flip();
        GL11.glLightfv(GL11.GL_LIGHT0, GL11.GL_DIFFUSE, diffuseLight);

        FloatBuffer specularLight = BufferUtils.createFloatBuffer(4).put(new float[]{1.0f, 1.0f, 1.0f, 1.0f});
        specularLight.flip();
        GL11.glLightfv(GL11.GL_LIGHT0, GL11.GL_SPECULAR, specularLight);

        GL11.glEnable(GL11.GL_COLOR_MATERIAL);
        GL11.glColorMaterial(GL11.GL_FRONT_AND_BACK, GL11.GL_AMBIENT_AND_DIFFUSE);
    }

    private void setPerspectiveProjection(float fov, float aspect, float zNear, float zFar) {
        float ymax = (float) (zNear * Math.tan(Math.toRadians(fov / 2.0)));
        float xmax = ymax * aspect;

        GL11.glMatrixMode(GL11.GL_PROJECTION);
        GL11.glLoadIdentity();
        GL11.glFrustum(-xmax, xmax, -ymax, ymax, zNear, zFar);
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
    }

    private void gluLookAt(float eyeX, float eyeY, float eyeZ, float centerX, float centerY, float centerZ, float upX, float upY, float upZ) {
        float[] forward = {centerX - eyeX, centerY - eyeY, centerZ - eyeZ};
        normalize(forward);

        float[] up = {upX, upY, upZ};
        float[] side = crossProduct(forward, up);
        normalize(side);

        up = crossProduct(side, forward);

        FloatBuffer viewMatrix = BufferUtils.createFloatBuffer(16);
        viewMatrix.put(new float[] {
                side[0], up[0], -forward[0], 0,
                side[1], up[1], -forward[1], 0,
                side[2], up[2], -forward[2], 0,
                -dotProduct(side, new float[]{eyeX, eyeY, eyeZ}),
                -dotProduct(up, new float[]{eyeX, eyeY, eyeZ}),
                dotProduct(forward, new float[]{eyeX, eyeY, eyeZ}),
                1
        });
        viewMatrix.flip();
        GL11.glMultMatrixf(viewMatrix);
    }

    private void normalize(float[] v) {
        float length = (float) Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
        if (length != 0) {
            v[0] /= length;
            v[1] /= length;
            v[2] /= length;
        }
    }

    private float[] crossProduct(float[] a, float[] b) {
        return new float[]{
                a[1] * b[2] - a[2] * b[1],
                a[2] * b[0] - a[0] * b[2],
                a[0] * b[1] - a[1] * b[0]
        };
    }

    private float dotProduct(float[] a, float[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }
}

class Player {
    private float x, y, z;
    private float targetX = 0;
    private float jumpVelocity = 0;
    private boolean isJumping = false;
    private float gravity = -9.8f;

    private float laneWidth;
    private float moveSpeed;



    public Player(float x, float y, float z) {
        this.x = x;
        this.y = y;
        this.z = 10.0f;  // Start further back
        this.laneWidth = 10000000.0f;  // Narrower lanes
        this.moveSpeed = 16.0f;  // Faster movement
    }

    public float getX() { return x; }
    public float getY() { return y; }
    public float getZ() { return z; }

    public void moveLeft(float deltaTime) {
        x += moveSpeed * deltaTime; // Move left when left key pressed
        x = Math.max(x, -laneWidth); // Limit to left boundary
    }

    public void moveRight(float deltaTime) {
        x -= moveSpeed * deltaTime; // Move right when right key pressed
        x = Math.min(x, laneWidth); // Limit to right boundary
    }
    public void jump() {
        if (!isJumping) {
            jumpVelocity = 5.0f;
            isJumping = true;
        }
    }

    public void update(float deltaTime) {
        // Smooth horizontal movement
        x += (targetX - x) * 10f * deltaTime;

        x = Math.max(-laneWidth, Math.min(laneWidth, x));

        // Jump physics
        if (isJumping) {
            y += jumpVelocity * deltaTime;
            jumpVelocity += gravity * deltaTime;

            if (y <= 0) {
                y = 0;
                isJumping = false;
                jumpVelocity = 0;
            }
        }
    }

    public void render(Terrain terrain) {
        float playerHeight = 1.8f;

        GL11.glPushMatrix();
        GL11.glTranslatef(x, y + playerHeight/2, z);

        // Render player (simple character)
        GL11.glColor3f(0.0f, 0.0f, 1.0f); // Blue color
        GL11.glShadeModel(GL11.GL_SMOOTH);

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
        GL11.glVertex3f(-0.5f, -0.5f, 0.5f);
        GL11.glVertex3f(0.5f, -0.5f, 0.5f);
        GL11.glVertex3f(0.5f, 0.5f, 0.5f);
        GL11.glVertex3f(-0.5f, 0.5f, 0.5f);

        // Back face
        GL11.glVertex3f(-0.5f, -0.5f, -0.5f);
        GL11.glVertex3f(0.5f, -0.5f, -0.5f);
        GL11.glVertex3f(0.5f, 0.5f, -0.5f);
        GL11.glVertex3f(-0.5f, 0.5f, -0.5f);

        // Left face
        GL11.glVertex3f(-0.5f, -0.5f, -0.5f);
        GL11.glVertex3f(-0.5f, -0.5f, 0.5f);
        GL11.glVertex3f(-0.5f, 0.5f, 0.5f);
        GL11.glVertex3f(-0.5f, 0.5f, -0.5f);

        // Right face
        GL11.glVertex3f(0.5f, -0.5f, -0.5f);
        GL11.glVertex3f(0.5f, -0.5f, 0.5f);
        GL11.glVertex3f(0.5f, 0.5f, 0.5f);
        GL11.glVertex3f(0.5f, 0.5f, -0.5f);

        // Top face
        GL11.glVertex3f(-0.5f, 0.5f, -0.5f);
        GL11.glVertex3f(0.5f, 0.5f, -0.5f);
        GL11.glVertex3f(0.5f, 0.5f, 0.5f);
        GL11.glVertex3f(-0.5f, 0.5f, 0.5f);

        // Bottom face
        GL11.glVertex3f(-0.5f, -0.5f, -0.5f);
        GL11.glVertex3f(0.5f, -0.5f, -0.5f);
        GL11.glVertex3f(0.5f, -0.5f, 0.5f);
        GL11.glVertex3f(-0.5f, -0.5f, 0.5f);
        GL11.glEnd();
    }

    private void renderSphere() {
        int slices = 16;
        int stacks = 16;

        for (int i = 0; i < stacks; ++i) {
            double lat0 = Math.PI * (-0.5 + (double) (i - 1) / stacks);
            double z0 = Math.sin(lat0);
            double zr0 = Math.cos(lat0);

            double lat1 = Math.PI * (-0.5 + (double) i / stacks);
            double z1 = Math.sin(lat1);
            double zr1 = Math.cos(lat1);

            GL11.glBegin(GL11.GL_QUAD_STRIP);
            for (int j = 0; j <= slices; ++j) {
                double lng = 2 * Math.PI * (double) (j - 1) / slices;
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

    public boolean collidesWith(Obstacle obstacle) {
        if (isJumping) return false; // Can jump over obstacles

        float playerWidth = 0.4f;
        float playerDepth = 0.2f;

        return Math.abs(x - obstacle.getX()) < (playerWidth + obstacle.getWidth())/2 &&
                Math.abs(z - obstacle.getZ()) < (playerDepth + obstacle.getDepth())/2;
    }
}

class Obstacle {
    private float x, y, z;
    private float width = 0.8f;
    private float height = 1.0f;
    private float depth = 0.8f;

    public Obstacle(float x, float y, float z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public float getX() { return x; }
    public float getY() { return y; }
    public float getZ() { return z; }
    public float getWidth() { return width; }
    public float getDepth() { return depth; }

    public void update(float gameSpeed) {
        z -= gameSpeed; // Move toward player
    }


    public void render() {
        GL11.glPushMatrix();
        GL11.glTranslatef(x, y + height/2, z);

        GL11.glColor3f(1.0f, 0.0f, 0.0f); // Red color
        GL11.glShadeModel(GL11.GL_SMOOTH);

        GL11.glScalef(width, height, depth);
        renderCube();

        GL11.glPopMatrix();
    }

    private void renderCube() {
        GL11.glBegin(GL11.GL_QUADS);
        // Front face
        GL11.glNormal3f(0, 0, 1);
        GL11.glVertex3f(-0.5f, -0.5f, 0.5f);
        GL11.glVertex3f(0.5f, -0.5f, 0.5f);
        GL11.glVertex3f(0.5f, 0.5f, 0.5f);
        GL11.glVertex3f(-0.5f, 0.5f, 0.5f);

        // Back face
        GL11.glVertex3f(-0.5f, -0.5f, -0.5f);
        GL11.glVertex3f(0.5f, -0.5f, -0.5f);
        GL11.glVertex3f(0.5f, 0.5f, -0.5f);
        GL11.glVertex3f(-0.5f, 0.5f, -0.5f);

        // Left face
        GL11.glVertex3f(-0.5f, -0.5f, -0.5f);
        GL11.glVertex3f(-0.5f, -0.5f, 0.5f);
        GL11.glVertex3f(-0.5f, 0.5f, 0.5f);
        GL11.glVertex3f(-0.5f, 0.5f, -0.5f);

        // Right face
        GL11.glVertex3f(0.5f, -0.5f, -0.5f);
        GL11.glVertex3f(0.5f, -0.5f, 0.5f);
        GL11.glVertex3f(0.5f, 0.5f, 0.5f);
        GL11.glVertex3f(0.5f, 0.5f, -0.5f);

        // Top face
        GL11.glVertex3f(-0.5f, 0.5f, -0.5f);
        GL11.glVertex3f(0.5f, 0.5f, -0.5f);
        GL11.glVertex3f(0.5f, 0.5f, 0.5f);
        GL11.glVertex3f(-0.5f, 0.5f, 0.5f);

        // Bottom face
        GL11.glVertex3f(-0.5f, -0.5f, -0.5f);
        GL11.glVertex3f(0.5f, -0.5f, -0.5f);
        GL11.glVertex3f(0.5f, -0.5f, 0.5f);
        GL11.glVertex3f(-0.5f, -0.5f, 0.5f);
        GL11.glEnd();
    }
}


class OBJLoader {
    public static Model loadModel(String fileName) throws IOException {
        BufferedReader reader = new BufferedReader(new FileReader(fileName));
        String line;
        List<float[]> vertices = new ArrayList<>();
        List<float[]> normals = new ArrayList<>();
        List<int[]> faces = new ArrayList<>();

        while ((line = reader.readLine()) != null) {
            String[] tokens = line.split("\\s+");
            if (tokens[0].equals("v")) {
                float[] vertex = {
                        Float.parseFloat(tokens[1]),
                        Float.parseFloat(tokens[2]),
                        Float.parseFloat(tokens[3])
                };
                vertices.add(vertex);
            }
            else if (tokens[0].equals("vn")) {
                float[] normal = {
                        Float.parseFloat(tokens[1]),
                        Float.parseFloat(tokens[2]),
                        Float.parseFloat(tokens[3])
                };
                normals.add(normal);
            }
            else if (tokens[0].equals("f")) {
                String[] v1 = tokens[1].split("/");
                String[] v2 = tokens[2].split("/");
                String[] v3 = tokens[3].split("/");

                int[] face = {
                        Integer.parseInt(v1[0])-1,
                        v1.length>2 ? Integer.parseInt(v1[2])-1 : 0,
                        Integer.parseInt(v2[0])-1,
                        v2.length>2 ? Integer.parseInt(v2[2])-1 : 0,
                        Integer.parseInt(v3[0])-1,
                        v3.length>2 ? Integer.parseInt(v3[2])-1 : 0
                };
                faces.add(face);
            }
        }

        float[] verticesArray = new float[vertices.size()*3];
        float[] normalsArray = new float[normals.size()*3];
        int[] indicesArray = new int[faces.size()*3];
        float[] normalIndicesArray = new float[faces.size()*3];

        int vertexIndex = 0;
        for (float[] vertex : vertices) {
            verticesArray[vertexIndex++] = vertex[0];
            verticesArray[vertexIndex++] = vertex[1];
            verticesArray[vertexIndex++] = vertex[2];
        }

        int normalIndex = 0;
        for (float[] normal : normals) {
            normalsArray[normalIndex++] = normal[0];
            normalsArray[normalIndex++] = normal[1];
            normalsArray[normalIndex++] = normal[2];
        }

        int faceIndex = 0;
        for (int[] face : faces) {
            indicesArray[faceIndex] = face[0];
            normalIndicesArray[faceIndex++] = face[1];
            indicesArray[faceIndex] = face[2];
            normalIndicesArray[faceIndex++] = face[3];
            indicesArray[faceIndex] = face[4];
            normalIndicesArray[faceIndex++] = face[5];
        }

        reader.close();
        return new Model(verticesArray, normalsArray, indicesArray, normalIndicesArray);
    }
}

class Model {
    private float[] vertices;
    private float[] normals;
    private int[] indices;
    private float[] normalIndices;

    public Model(float[] vertices, float[] normals, int[] indices, float[] normalIndices) {
        this.vertices = vertices;
        this.normals = normals;
        this.indices = indices;
        this.normalIndices = normalIndices;
    }

    public float[] getVertices() { return vertices; }
    public float[] getNormals() { return normals; }
    public int[] getIndices() { return indices; }
    public float[] getNormalIndices() { return normalIndices; }
}

class Terrain {
    private float[] vertices;
    private float[] normals;
    private int[] indices;

    public Terrain() {
        createStraightTrack();
    }

    private void createStraightTrack() {
        // Create a straight path with 3 lanes
        int segments = 100;
        float width = 6.0f;  // 3 lanes
        float length = 100.0f;

        vertices = new float[segments * 2 * 3];
        normals = new float[segments * 2 * 3];
        indices = new int[(segments - 1) * 6];

        // Create vertices
        for (int i = 0; i < segments; i++) {
            float z = -length/2 + (length * i / (segments - 1));

            // Left side
            vertices[i*6] = -width/2;
            vertices[i*6+1] = 0;
            vertices[i*6+2] = z;

            // Right side
            vertices[i*6+3] = width/2;
            vertices[i*6+4] = 0;
            vertices[i*6+5] = z;

            // Normals (pointing up)
            normals[i*6] = 0; normals[i*6+1] = 1; normals[i*6+2] = 0;
            normals[i*6+3] = 0; normals[i*6+4] = 1; normals[i*6+5] = 0;
        }

        // Create indices
        for (int i = 0; i < segments - 1; i++) {
            indices[i*6] = i*2;
            indices[i*6+1] = i*2+1;
            indices[i*6+2] = i*2+2;
            indices[i*6+3] = i*2+1;
            indices[i*6+4] = i*2+3;
            indices[i*6+5] = i*2+2;
        }
    }

    public void render() {
        GL11.glColor3f(0.2f, 0.6f, 0.3f);  // Grass green color

        GL11.glBegin(GL11.GL_TRIANGLES);
        for (int i = 0; i < indices.length; i++) {
            int vertexIndex = indices[i] * 3;
            int normalIndex = indices[i] * 3;

            GL11.glNormal3f(normals[normalIndex], normals[normalIndex+1], normals[normalIndex+2]);
            GL11.glVertex3f(vertices[vertexIndex], vertices[vertexIndex+1], vertices[vertexIndex+2]);
        }
        GL11.glEnd();

        // Draw lane markers
        GL11.glColor3f(1.0f, 1.0f, 1.0f);
        GL11.glBegin(GL11.GL_LINES);
        for (float z = -50; z < 50; z += 2.0f) {
            GL11.glVertex3f(-2.0f, 0.01f, z);
            GL11.glVertex3f(-2.0f, 0.01f, z + 1.0f);

            GL11.glVertex3f(2.0f, 0.01f, z);
            GL11.glVertex3f(2.0f, 0.01f, z + 1.0f);
        }
        GL11.glEnd();
    }

    public float getTerrainHeightAt(float x, float z) {
        // Simple flat terrain
        return 0.0f;
    }

    private boolean isPointInTriangle(float px, float pz, float v1X, float v1Z, float v2X, float v2Z, float v3X, float v3Z) {
        float d1 = sign(px, pz, v1X, v1Z, v2X, v2Z);
        float d2 = sign(px, pz, v2X, v2Z, v3X, v3Z);
        float d3 = sign(px, pz, v3X, v3Z, v1X, v1Z);

        boolean hasNeg = (d1 < 0) || (d2 < 0) || (d3 < 0);
        boolean hasPos = (d1 > 0) || (d2 > 0) || (d3 > 0);
        return !(hasNeg && hasPos);
    }

    private float sign(float px, float pz, float v1X, float v1Z, float v2X, float v2Z) {
        return (px-v2X)*(v1Z-v2Z)-(v1X-v2X)*(pz-v2Z);
    }

    private float interpolateHeight(float x, float z, float v1X, float v1Y, float v1Z,
                                    float v2X, float v2Y, float v2Z, float v3X, float v3Y, float v3Z) {
        float areaTotal = triangleArea(v1X, v1Z, v2X, v2Z, v3X, v3Z);
        float area1 = triangleArea(x, z, v2X, v2Z, v3X, v3Z);
        float area2 = triangleArea(x, z, v3X, v3Z, v1X, v1Z);
        float area3 = triangleArea(x, z, v1X, v1Z, v2X, v2Z);

        float weight1 = area1/areaTotal;
        float weight2 = area2/areaTotal;
        float weight3 = area3/areaTotal;

        return weight1*v1Y + weight2*v2Y + weight3*v3Y;
    }

    private float triangleArea(float x1, float z1, float x2, float z2, float x3, float z3) {
        return Math.abs((x1*(z2-z3) + x2*(z3-z1) + x3*(z1-z2))/2.0f);
    }
}
