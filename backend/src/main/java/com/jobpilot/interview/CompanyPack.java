package com.jobpilot.interview;

import com.jobpilot.tailor.Tailor;

import java.util.List;
import java.util.Optional;

/**
 * Mock interviews in the style of the big fresher recruiters' rounds, from how those rounds are publicly described
 * (technical, managerial, HR). The questions are generated fresh each time; nothing here is a real or leaked question,
 * and the prompt says so. A pack session is titled with {@link #title()}, which is how a running session finds its pack.
 *
 * @param plan what each of the 8 questions covers (index 0 = the opening), like Interview's level plans
 */
public record CompanyPack(String id, String company, String role, String rounds, String style, List<String> plan) {

    public String title() { return role + " at " + company; }

    Tailor.JobInfo job() { return new Tailor.JobInfo(role, company, style, List.of()); }

    static final List<CompanyPack> ALL = List.of(
            new CompanyPack("tcs", "TCS", "Systems Engineer (fresher)", "Technical, Managerial, HR",
                    "Style of TCS's fresher hiring (NQT -> Ninja / Digital): one panel covering a technical round, a managerial round and an HR round. "
                            + "Technical checks final-year project depth, OOP, DBMS/SQL and simple coding logic explained aloud; managerial checks how they handle "
                            + "pressure, teams and clients; HR checks communication, flexibility to relocate and work in shifts, and why TCS.",
                    List.of("opening",
                            "their final-year or main project: their own part and the technology choices",
                            "OOP: the four pillars with a small example from their project or language",
                            "DBMS/SQL: keys and normalisation, or write a simple query aloud (joins or GROUP BY)",
                            "simple coding logic explained aloud: e.g. reverse a string, check a palindrome or prime, find duplicates, with its complexity",
                            "managerial: a situation with a tight deadline, a difficult teammate or an unhappy client, and what they did",
                            "managerial: how they learn a technology they don't know when a project needs it quickly",
                            "HR: why TCS, and are they comfortable relocating to any city and working in shifts")),
            new CompanyPack("infosys", "Infosys", "Systems Engineer (fresher)", "Technical, HR",
                    "Style of Infosys's fresher hiring (Systems Engineer / Specialist Programmer): a technical interview on fundamentals and projects, "
                            + "then HR. Technical favours programming basics in their chosen language, OOP, DBMS and a puzzle or logic question; HR checks "
                            + "communication, learning attitude and readiness for long training and relocation.",
                    List.of("opening",
                            "their main project: the problem it solved and their exact contribution",
                            "programming basics in their language: e.g. difference between overloading and overriding, or how memory is managed",
                            "DBMS: normalisation, primary vs foreign keys, or a simple SQL query explained aloud",
                            "a logic puzzle or an easy coding problem explained aloud with its approach (no full code)",
                            "a current technology they are interested in (cloud, AI, etc.) and what they know about it",
                            "behavioural: a time they learnt something difficult on their own (STAR)",
                            "HR: why Infosys, strengths and a weakness, and flexibility about training location")),
            new CompanyPack("wipro", "Wipro", "Project Engineer (fresher)", "Technical, HR",
                    "Style of Wipro's fresher hiring (Elite NTH / Turbo): a technical round on programming fundamentals, projects and basic SQL, then HR "
                            + "on communication, attitude and flexibility.",
                    List.of("opening",
                            "their project: architecture in simple terms and the hardest part",
                            "C/Java/Python fundamentals: e.g. pointers or references, exception handling, or collections",
                            "SQL: write a query aloud for a simple scenario, or explain joins",
                            "an easy array or string problem explained aloud with its complexity",
                            "software engineering basics: SDLC, agile, or how they test their code",
                            "behavioural: working in a team project, and a conflict they resolved (STAR)",
                            "HR: why Wipro, career goals for the next 3 years, and relocation or shift flexibility")),
            new CompanyPack("accenture", "Accenture", "Associate Software Engineer", "Communication, Technical, HR",
                    "Style of Accenture's fresher hiring (ASE): communication is weighed heavily; the technical interview covers projects, programming "
                            + "and cloud/IT basics; HR covers client-facing attitude, shifts and relocation.",
                    List.of("opening",
                            "explain their project to a non-technical client in simple words",
                            "programming fundamentals in their main language with a small example",
                            "cloud and IT basics: what the cloud is, SaaS/PaaS/IaaS, or how a web request works",
                            "an easy coding problem explained aloud with its approach",
                            "client situation: a client changes requirements late; how they respond",
                            "behavioural: a time they took initiative or led something (STAR)",
                            "HR: why Accenture, and comfort with shifts, relocation and continuous learning")),
            new CompanyPack("cognizant", "Cognizant", "Programmer Analyst Trainee (GenC)", "Technical, HR",
                    "Style of Cognizant's fresher hiring (GenC / GenC Next / GenC Elevate): a technical interview on OOP, DBMS, DSA basics and projects, "
                            + "deeper for the higher tracks, then HR.",
                    List.of("opening",
                            "their project: their role, the stack and why",
                            "OOP concepts with examples: inheritance vs composition, polymorphism",
                            "DBMS/SQL: normalisation, indexes, or a query with a join explained aloud",
                            "a data structures question explained aloud: arrays, linked lists, stacks or queues, with complexity",
                            "operating systems or networks basics: processes vs threads, or what happens when you open a website",
                            "behavioural: handling pressure or a failure in a project (STAR)",
                            "HR: why Cognizant, and openness to relocation and night shifts")),
            new CompanyPack("capgemini", "Capgemini", "Analyst (fresher)", "Technical, HR",
                    "Style of Capgemini's fresher hiring (Analyst / Senior Analyst): technical on projects, pseudo-code reasoning and fundamentals, then "
                            + "HR with emphasis on communication.",
                    List.of("opening",
                            "their project: what it does and their contribution",
                            "pseudo-code reasoning: walk through what a short loop or recursion does, aloud",
                            "OOP or language fundamentals with a small example",
                            "SQL or DBMS basics with a simple scenario",
                            "an easy coding problem explained aloud with its complexity",
                            "behavioural: a team situation and how they communicated (STAR)",
                            "HR: why Capgemini, and flexibility on location and technology")),
            new CompanyPack("hcltech", "HCLTech", "Graduate Engineer Trainee", "Technical, HR",
                    "Style of HCLTech's fresher hiring: technical on fundamentals, projects and basic networking or OS, then HR.",
                    List.of("opening",
                            "their project: the problem, design and their part",
                            "programming fundamentals in their main language",
                            "operating systems or computer networks basics, explained simply",
                            "DBMS/SQL fundamentals with an example",
                            "an easy coding or logic problem explained aloud",
                            "behavioural: learning under time pressure or helping a teammate (STAR)",
                            "HR: why HCLTech, and comfort with shifts and relocation")));

    public static List<CompanyPack> all() { return ALL; }

    public static Optional<CompanyPack> byId(String id) { return ALL.stream().filter(p -> p.id.equalsIgnoreCase(id)).findFirst(); }

    /** The pack a session belongs to, from its title (null for job or role sessions). */
    static Optional<CompanyPack> forTitle(String title) { return ALL.stream().filter(p -> p.title().equals(title)).findFirst(); }

    /** The pack a job description came from (sessions keep a JobInfo, not the pack). */
    static Optional<CompanyPack> forJob(Tailor.JobInfo job) {
        return ALL.stream().filter(p -> p.role.equals(job.title()) && p.company.equals(job.company())).findFirst();
    }
}
