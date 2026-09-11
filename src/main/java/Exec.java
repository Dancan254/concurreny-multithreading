import static java.lang.IO.println;

void main() {
//    String name = null;
//    println(name.toLowerCase());

//    int result = divide(90, 0);
//    println(result);

    readFile(Path.of("src/main/resources/file.txt"));
}

void readFile(Path path) {

    try (FileReader fr = new FileReader(path.toFile());
         BufferedReader reader = new BufferedReader(fr)) {
        reader.readAllLines();
    } catch (FileNotFoundException e){
        throw new CustomFileNotFoundExcetion("File not found");
    }
    catch (IOException e) {
        throw new RuntimeException(e);
    }

}

int divide(int a, int b) throws NullPointerException{
    try {
        return a / b;
    } catch (ArithmeticException e) {
        println("Divide by zero");
        return 0;
    } catch (Exception e) {
        throw new RuntimeException(e);
    } finally {
        println("Finally");
        return 1;
    }
}

class CustomFileNotFoundExcetion extends RuntimeException {

    public CustomFileNotFoundExcetion(String message){
        super(message);
    }
}