package org.example.shape;

// 抽象父类：图形
abstract class Shape {
    // private: 完全封装，子类、外部类不能直接访问
    private String name;

    // 父类带参构造，负责给自己的name赋值
    public Shape(String name) {
        this.name = name;
    }

    // getter，只读，没有setName，对象创建后名称不可修改，更安全
    public String getName() {
        return name;
    }

    // 周长、面积每个图形算法不一样，定义抽象方法
    public abstract double getPerimeter();

    public abstract double getArea();

    // 统一打印逻辑，父类复用
    public void printInfo() {
        System.out.println("图形名称: " + getName());
        System.out.printf("周长 = %.2f%n", getPerimeter());
        System.out.printf("面积 = %.2f%n", getArea());
    }
}

// 矩形子类
class Rectangle extends Shape {
    // 私有，只属于矩形
    private double width;
    private double height;

    // 子类构造：把name交给父类super处理，自己处理长宽
    public Rectangle(String name, double width, double height) {
        super(name);
        this.width = width;
        this.height = height;
    }

    @Override
    public double getPerimeter() {
        return 2 * (width + height);
    }

    @Override
    public double getArea() {
        return width * height;
    }
}

// 圆形子类
class Circle extends Shape {
    private double radius;

    public Circle(String name, double radius) {
        super(name);
        this.radius = radius;
    }

    @Override
    public double getPerimeter() {
        return 2 * Math.PI * radius;
    }

    @Override
    public double getArea() {
        return Math.PI * radius * radius;
    }
}

// 测试类，演示多态
public class ShapeDemo {
    public static void main(String[] args) {
        // 多态：父类引用指向子类实例
        Shape rect = new Rectangle("矩形", 3, 4);
        rect.printInfo();

        System.out.println("----------");

        Shape circle = new Circle("圆形", 5);
        circle.printInfo();
    }
}
