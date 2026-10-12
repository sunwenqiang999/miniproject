#include <iostream>
#include <iomanip>

using namespace std;

int main() {
    double r, s;
    const double PI = 3.141593;

    cin >> r;
    s = PI * r * r;

    if (r >= 1 && r <= 10 && s >= 40 && s <= 90) {
        cout << fixed << setprecision(2) << s << endl;
    } else {
        cout << "NO" << endl;
    }

    return 0;
}
