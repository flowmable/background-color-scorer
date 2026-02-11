package com.flowmable.scorer;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Path;
import java.util.*;

public class ComponentAnalyzer {

    public static void main(String[] args) throws Exception {
        Path designPath = Path.of("src/main/resources/designs/design (4).png");
        BufferedImage image = ImageIO.read(designPath.toFile());
        System.out.println("Analyzed " + designPath);
        System.out.println("Size: " + image.getWidth() + "x" + image.getHeight());

        List<Component> components = findComponents(image);
        
        System.out.println("Found " + components.size() + " components (sorted by Y position):");
        components.sort(Comparator.comparingInt(c -> c.minY));

        for (int i = 0; i < components.size(); i++) {
            Component c = components.get(i);
            System.out.printf("Component %d: Bounds[x=%d, y=%d, w=%d, h=%d] Pixels=%d Center=(%d, %d)%n",
                    i, c.minX, c.minY, c.width(), c.height(), c.pixelCount, c.centerX(), c.centerY());
        }
    }

    private static List<Component> findComponents(BufferedImage image) {
        int w = image.getWidth();
        int h = image.getHeight();
        boolean[][] visited = new boolean[w][h];
        List<Component> components = new ArrayList<>();

        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (visited[x][y]) continue;
                
                int alpha = (image.getRGB(x, y) >> 24) & 0xFF;
                if (alpha < 10) continue; // Skip transparent

                // Start BFS
                Component comp = new Component();
                Queue<Point> queue = new ArrayDeque<>();
                queue.add(new Point(x, y));
                visited[x][y] = true;
                comp.add(x, y);

                while (!queue.isEmpty()) {
                    Point p = queue.poll();
                    
                    // Check neighbors
                    int[] dx = {1, -1, 0, 0};
                    int[] dy = {0, 0, 1, -1};
                    
                    for (int i = 0; i < 4; i++) {
                        int nx = p.x + dx[i];
                        int ny = p.y + dy[i];
                        
                        if (nx >= 0 && nx < w && ny >= 0 && ny < h && !visited[nx][ny]) {
                            int nAlpha = (image.getRGB(nx, ny) >> 24) & 0xFF;
                            if (nAlpha >= 10) { // Threshold for "part of design"
                                visited[nx][ny] = true;
                                comp.add(nx, ny);
                                queue.add(new Point(nx, ny));
                            }
                        }
                    }
                }
                
                if (comp.pixelCount > 50) { // Filter noise
                    components.add(comp);
                }
            }
        }
        return components;
    }

    static class Point { int x, y; Point(int x, int y) { this.x = x; this.y = y; } }
    
    static class Component {
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        int pixelCount = 0;
        
        void add(int x, int y) {
            minX = Math.min(minX, x);
            maxX = Math.max(maxX, x);
            minY = Math.min(minY, y);
            maxY = Math.max(maxY, y);
            pixelCount++;
        }
        
        int width() { return maxX - minX + 1; }
        int height() { return maxY - minY + 1; }
        int centerX() { return (minX + maxX) / 2; }
        int centerY() { return (minY + maxY) / 2; }
    }
}
