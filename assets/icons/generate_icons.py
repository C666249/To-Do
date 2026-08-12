from PIL import Image, ImageDraw

sizes = {
    'mdpi': 48,
    'hdpi': 72,
    'xhdpi': 96,
    'xxhdpi': 144,
    'xxxhdpi': 192
}

for density, size in sizes.items():
    img = Image.new('RGBA', (size, size), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)
    
    margin = int(size * 0.15)
    inner_size = size - margin * 2
    
    bg_color = (26, 26, 46)
    draw.rectangle([0, 0, size, size], fill=bg_color)
    
    def gradient_color(t):
        r = int(102 + (118 - 102) * t)
        g = int(126 + (75 - 126) * t)
        b = int(234 + (162 - 234) * t)
        return (r, g, b)
    
    line_width = max(1, int(size * 0.03))
    half_line = line_width // 2
    
    x1 = margin
    x2 = size - margin
    y1 = margin
    y2 = size - margin
    
    for i in range(line_width):
        t = i / (line_width - 1) if line_width > 1 else 0.5
        color = gradient_color(t)
        
        draw.rectangle([x1 + i, y1 + i, x2 - i, y2 - i], outline=color, width=1)
    
    mid_y = (y1 + y2) // 2
    line_y1 = y1 + int((y2 - y1) * 0.35)
    line_y2 = y1 + int((y2 - y1) * 0.55)
    line_y3 = y1 + int((y2 - y1) * 0.7)
    
    for i in range(line_width):
        t = i / (line_width - 1) if line_width > 1 else 0.5
        color = gradient_color(t)
        draw.line([(x1 + int((x2 - x1) * 0.3) + i, line_y1), (x2 - i, line_y1)], fill=color, width=1)
        draw.line([(x1 + int((x2 - x1) * 0.5) + i, line_y2), (x2 - i, line_y2)], fill=color, width=1)
        draw.line([(x1 + int((x2 - x1) * 0.25) + i, line_y3), (x2 - i, line_y3)], fill=color, width=1)
    
    spiral_center_x = x1 + int((x2 - x1) * 0.1)
    spacing = int((y2 - y1) / 7)
    
    for i in range(6):
        cy = y1 + spacing + i * spacing
        for j in range(line_width):
            t = j / (line_width - 1) if line_width > 1 else 0.5
            color = gradient_color(t)
            draw.ellipse([spiral_center_x - 4 + j, cy - 4, spiral_center_x + 4 + j, cy + 4], outline=color, width=1)
    
    bookmark_x = x1 + int((x2 - x1) * 0.6)
    bookmark_y1 = mid_y
    bookmark_y2 = y2 - margin // 2
    bookmark_width = int((x2 - x1) * 0.35)
    
    for j in range(line_width):
        t = j / (line_width - 1) if line_width > 1 else 0.5
        color = gradient_color(t)
        draw.rectangle([bookmark_x + j, bookmark_y1 + j, bookmark_x + bookmark_width - j, bookmark_y2 - j], outline=color, width=1)
    
    circle_x = bookmark_x + bookmark_width // 2
    circle_y = bookmark_y1 + (bookmark_y2 - bookmark_y1) // 3
    circle_radius = int(bookmark_width * 0.15)
    
    for j in range(line_width):
        t = j / (line_width - 1) if line_width > 1 else 0.5
        color = gradient_color(t)
        draw.ellipse([circle_x - circle_radius + j, circle_y - circle_radius, circle_x + circle_radius + j, circle_y + circle_radius], outline=color, width=1)
    
    corner_r = int(size * 0.05)
    star1_pos = (int(size * 0.15), int(size * 0.18))
    star2_pos = (int(size * 0.12), int(size * 0.8))
    circle_pos = (int(size * 0.9), int(size * 0.85))
    
    def draw_star(draw, pos, r, color):
        x, y = pos
        points = []
        for i in range(5):
            angle = i * 72 - 90
            px = x + r * (1 if i % 2 == 0 else 0.5) * (angle * 3.14159 / 180)
            py = y + r * (1 if i % 2 == 0 else 0.5) * (angle * 3.14159 / 180)
            points.append((px, py))
        draw.polygon(points, fill=color)
    
    draw_star(draw, star1_pos, int(size * 0.04), gradient_color(0.3))
    draw_star(draw, star2_pos, int(size * 0.03), gradient_color(0.7))
    draw.ellipse([circle_pos[0] - int(size * 0.03), circle_pos[1] - int(size * 0.03), circle_pos[0] + int(size * 0.03), circle_pos[1] + int(size * 0.03)], fill=gradient_color(0.5))
    
    arrow_size = int(size * 0.05)
    arrow_pos = (int(size * 0.88), int(size * 0.2))
    arrow_points = [
        (arrow_pos[0] - arrow_size, arrow_pos[1]),
        (arrow_pos[0], arrow_pos[1] - arrow_size),
        (arrow_pos[0] + arrow_size, arrow_pos[1])
    ]
    draw.polygon(arrow_points, fill=gradient_color(0.6))
    
    img_path = f"D:\\Claude\\projects\\To-Do List\\android\\app\\src\\main\\res\\mipmap-{density}\\ic_launcher.png"
    img.save(img_path)
    print(f"Generated: {img_path}")

print("All icons generated!")
