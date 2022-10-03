libsl "1.0.0";
library targetAnnotation;

types {
   Int(int32); // simple type
   A(Int);
}

automaton B : Int {}

automaton A : Int {
    fun foo (@target self: B);
}
