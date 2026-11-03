public class ScanTest { public static void main(String[] a) throws Exception {
  for (String j : a) { long t=System.nanoTime(); String[] r = arautoconnect.Scanner.scan(j);
    System.out.println(String.join(" ", r) + "  (" + (System.nanoTime()-t)/1000000 + " ms) " + j); } } }
