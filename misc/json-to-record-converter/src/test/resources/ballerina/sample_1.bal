type SportsItem record {
	string question;
	string[] options;
	string answer;
};

type MathsItem record {
	string question;
	string[] options;
	string answer;
};

type NewRecord record {
	SportsItem[] sports;
	MathsItem[] maths;
};
