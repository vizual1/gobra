package main

// ##(-I src/test/resources/regressions/features/import/mpredicate_import)
import b "bar"

func foo() {
    r! := b.Rectangle{Width: 2, Height: 5}
    fold r.RectMem()
    assert r.Area() == 10
    unfold (*(b.Rectangle)).RectMem(&r)
    fold (*(b.Rectangle)).RectMem(&r)
    assert (*(b.Rectangle)).Area(&r) == 10
    unfold r.RectMem()
}
